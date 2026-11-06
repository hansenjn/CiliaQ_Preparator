package ciliaQ_Prep_jnh.ridge_detection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import ciliaQ_Prep_jnh.ProgressReporter;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.Junction;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.Line;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.LineDetector;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.Lines;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.OverlapOption;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * CiliaQ Preparator - RIDGE DETECTION in z-stacks: the "2D in-focus plane" and "3D" z methods (v0.2.1).
 *
 * Classic ridge detection (RidgeSegmenter) paints every plane a line is found in and joins lines of neighbouring
 * planes that share a pixel; out-of-focus planes are painted too, and filaments crossing at different depths fuse.
 * Here each detection is kept only in its IN-FOCUS PLANE and the kept points are joined to their 3D neighbours:
 *  1. Steger per plane (thresholds derived once per stack). Each line point carries its plane, response and direction.
 *  2. IN-FOCUS PLANE. Per point, the intensity profile through z at its position (smoothed in xy by the z-profile
 *     smoothing) is climbed to its peak; the peak's position is refined to sub-plane accuracy. Points on lines lying
 *     flat (at least 3 line widths long in their plane) take the peak of the local-variance profile instead (window
 *     half a line width): the sharpest plane, not the brightest. A profile much broader than typical (more than 2x the
 *     median half-maximum run of flat lines) belongs to a steep or standing filament: its points are kept across their
 *     half-maximum run. Otherwise a point is kept if its plane is the one nearest the peak, or if no aligned detection
 *     lies in a plane closer to it.
 *  3. COPY RULE (optional, with its halo rule). A kept point is an out-of-focus copy when a kept point at least
 *     copyRatio x brighter lies in another plane (up to 4 away), within 1.5 line widths and aligned within 30 deg, on a
 *     piece at least 3 widths long. A short piece (at most 4 widths) is dropped whole when every point of it has such a
 *     brighter partner on a longer piece (the out-of-focus halo of a short bright filament).
 *  4. CLEAN-UP RULES (RidgeCleanup) on the kept stretches of each plane: pairs of lines that may never be joined.
 *  5. JOINING. Consecutive kept points of a line, and points in neighbouring planes that are aligned and lie within a
 *     line width (plus one plane step) of each other at their fitted depths - best first (closest, best aligned).
 *  6. 3D only: Steger runs without overlap resolution here, so lines are cut at junctions. Where a filament crosses
 *     another at a different depth, the 2D resolution of one plane may pair the branches differently from the next.
 *     Instead, junctions of all planes that lie together form a crossing site; links through z are not trusted in its
 *     zone; every track ending at a site is measured outside the zone and the endings are paired for the whole stack
 *     (the straightest continuation, bend at most 60 deg). A track passing through a site is kept apart from the other
 *     long tracks there; permissive links then join everything else that is not known to differ.
 *     Two filaments running side by side into one ridge are traced apart at fine scale (the tracer). The branch and
 *     crossing rules read each plane as 2D does (Steger with overlap resolution) and do not bind the pairing at junctions.
 *  7. OBJECTS. The minimum length applies to the 3D object (kept points, plus its z extent beyond one plane). Each kept
 *     point is painted with the filament's physical size (1 px in xy, 1 px / (voxel depth / pixel width) planes in z),
 *     and the 26-connected pieces of one object are joined by straight segments.
 *
 * Distances in z use the voxel size (pixel depth / pixel width): set it (Image > Properties) before running.
 *
 * @author Jan N. Hansen
 */
final class RidgeZ {

	// fixed constants of the method (in line widths unless stated)
	static final double COS30 = Math.cos(Math.toRadians(30));
	static final double PROFILE_DIP = 0.8, PROFILE_TAU = 0.1, PROFILE_LOBES = 3, BROAD = 2, FLAT_LENGTH = 3, Z_DISC = 1;
	static final double COPY_REACH = 1.5, HALO_LENGTH = 4;
	static final int COPY_PLANES = 4;
	static final double XR = 1, OVERLAP = 6, COHERENCE = 15, KINK = 25, KINK_ARM = 2, SITE_ANGLE = 20, FIT = 3,
			UNPAIRED = 0.5, BEND = 60, LONG = 4;
	static final double TRACE_SIGMA = 1.0, TRACE_ANGLE = 40, TRACE_DIP = 0.9;
	static final double MIN_PIECE = 2; // px: Steger's minimum length per plane; the user's minimum applies to the
										// object

	final int W, H, D;
	final double a, w, tol;
	final RidgeSettings rs;
	final boolean threeD;
	final ImagePlus imp;
	final ProgressReporter progress;
	final List<float[]> P = new ArrayList<>(); // line points {x, y, z, |response|, tx, ty, lineId, index in line, points in line}
	final List<int[]> span = new ArrayList<>(); // per line: {first point, n, junction at start, junction at end}
	final List<float[]> J = new ArrayList<>(); // 3D: junctions {x, y, z}
	final List<List<int[]>> slopeSeq = new ArrayList<>(); // 3D: per plane, the lines as 2D sees them, as point indices
	final List<int[]> tracedPairs = new ArrayList<>();
	int nTraced, nLinesTotal;
	int[] par;
	Map<Integer, Set<Integer>> forb, forbPar; // union-find and the kept-apart pairs (hard / soft) by root
	final Map<Integer, Integer> longPiece = new HashMap<>(), armReach = new HashMap<>();
	final Map<Integer, double[]> armDirOf = new HashMap<>();
	final RidgeCleanup rules;

	private RidgeZ(ImagePlus imp, RidgeSettings rs, boolean threeD, ProgressReporter progress) {
		this.imp = imp;
		this.rs = rs;
		this.threeD = threeD;
		this.progress = progress;
		W = imp.getWidth();
		H = imp.getHeight();
		D = imp.getStackSize();
		double pw = imp.getCalibration().pixelWidth, pd = imp.getCalibration().pixelDepth;
		a = pw > 0 && pd > 0 ? pd / pw : 1;
		w = rs.lineWidth;
		tol = w / 2;
		rules = rs.cleanup();
	}

	/**
	 * the result: labels [plane][y * W + x] (0 = background; numbered by object,
	 * made consecutive by the caller) and the lines detected
	 */
	static final class Output {
		final int[][] labels;
		final int nLines;

		Output(int[][] l, int n) {
			labels = l;
			nLines = n;
		}
	}

	/**
	 * the in-focus (threeD false) or the 3D method on one single-channel,
	 * single-timepoint stack
	 */
	static Output detect(ImagePlus imp, RidgeSettings rs, boolean threeD, ProgressReporter progress,
			RidgeSegmenter.SlowWatchdog watchdog) {
		RidgeZ r = new RidgeZ(imp, rs, threeD, progress);
		int[][] lab = r.run(watchdog);
		return new Output(lab, r.nLinesTotal);
	}

	// ------------------------------------------------------------------ the method

	int[][] run(RidgeSegmenter.SlowWatchdog watchdog) {
		final boolean twoDLines = threeD && (rules.branch || rules.crossing);
		int lineId = 0;
		for (int z = 0; z < D; z++) {
			progress.updateBarText("Ridge detection slice " + (z + 1) + "/" + D);
			final int pz0 = P.size();
			ImageProcessor ip = imp.getStack().getProcessor(z + 1);
			LineDetector det = new LineDetector().setLineWidth(w);
			Lines ls; // the overlap resolution gets the user's width
			try {
				watchdog.arm();
				try {
					ls = det.detectLines(ip, rs.sigma, rs.upperThreshold, rs.lowerThreshold, MIN_PIECE, rs.maxLength,
							rs.darkLine, rs.correctPosition, rs.estimateWidth, rs.extendLine,
							threeD ? OverlapOption.NONE : OverlapOption.SLOPE);
				} finally {
					watchdog.disarm();
				}
				RidgeSegmenter.checkCap(ls.size(), W, H, z);
			} catch (IllegalStateException overCap) {
				throw overCap; // the safety cap is fatal for the whole channel
			} catch (Throwable e) { // any other failure on one plane must not lose the others: the plane counts as
									// empty
				progress.notifyMessage("CiliaQ Preparator: ridge detection failed on plane " + (z + 1) + " - " + e,
						ProgressReporter.NOTIFICATION);
				if (twoDLines)
					slopeSeq.add(new ArrayList<int[]>());
				continue;
			}
			nLinesTotal += ls.size();
			Map<Long, Integer> jAt = new HashMap<>();
			if (threeD)
				for (Junction j : det.getJunctions()) {
					long k = fkey(j.getX(), j.getY());
					if (!jAt.containsKey(k)) {
						jAt.put(k, J.size());
						J.add(new float[] { j.getX(), j.getY(), z });
					}
				}
			List<float[][]> LS = new ArrayList<>();
			for (Line l : ls)
				LS.add(new float[][] { l.getXCoordinates(), l.getYCoordinates(), l.getResponse() });
			Map<Integer, int[]> endJ = new HashMap<>();
			tracedLS.clear();
			final int lineBase = lineId;
			if (threeD)
				traceMerges(LS, endJ, jAt, z, (float[]) ip.convertToFloat().getPixels());
			for (int li = 0; li < LS.size(); li++) {
				float[][] Lq = LS.get(li);
				float[] X = Lq[0], Y = Lq[1], Rr = Lq[2];
				int n = X.length;
				if (threeD) { // a bend of at least KINK deg between the directions over w before and after a
								// point is a junction Steger did not mark
					int kb = Math.max(2, (int) Math.round(w));
					double[] turn = new double[n];
					for (int i = kb; i + kb < n; i++) {
						double ux = X[i] - X[i - kb], uy = Y[i] - Y[i - kb], vx = X[i + kb] - X[i],
								vy = Y[i + kb] - Y[i], nu = Math.hypot(ux, uy), nv = Math.hypot(vx, vy);
						if (nu > 0 && nv > 0)
							turn[i] = Math
									.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ux * vx + uy * vy) / (nu * nv)))));
					}
					int last = -1000;
					final int kArm = (int) Math.round(KINK_ARM * w);
					for (int i = kb; i + kb < n; i++)
						if (turn[i] >= KINK && turn[i] >= turn[i - 1] && turn[i] > turn[i + 1] && i - last > 2 * kb
								&& i >= kArm && n - 1 - i >= kArm) {
							last = i;
							J.add(new float[] { X[i], Y[i], z });
						}
				}
				int js0 = n > 0 ? jAt.getOrDefault(fkey(X[0], Y[0]), -1) : -1,
						je0 = n > 0 ? jAt.getOrDefault(fkey(X[n - 1], Y[n - 1]), -1) : -1;
				int[] ov = endJ.get(li);
				if (ov != null) {
					if (ov[0] != -2)
						js0 = ov[0];
					if (ov[1] != -2)
						je0 = ov[1];
				}
				span.add(new int[] { P.size(), n, js0, je0 });
				for (int i = 0; i < n; i++) {
					int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
					double tx = X[i1] - X[i0], ty = Y[i1] - Y[i0], tn = Math.hypot(tx, ty);
					if (tn == 0) {
						tx = 1;
						ty = 0;
						tn = 1;
					}
					P.add(new float[] { X[i], Y[i], z, Math.abs(Rr[i]), (float) (tx / tn), (float) (ty / tn), lineId, i,
							n });
				}
				lineId++;
			}
			if (twoDLines)
				slopeSeq.add(lines2D(ip, pz0));
			for (int[] tp : tracedLS) {
				int[] sa = span.get(lineBase + tp[0]), sc = span.get(lineBase + tp[1]);
				if (sa[1] > 0 && sc[1] > 0)
					tracedPairs.add(new int[] { sa[0], sc[0] });
			}
		}
		final int N = P.size();
		progress.updateBarText("Ridge detection: in-focus planes and joining (" + N + " points)");

		Map<Long, List<Integer>> grid = new HashMap<>();
		for (int i = 0; i < N; i++) {
			float[] p = P.get(i);
			grid.computeIfAbsent(key((int) p[2], (int) (p[0] / 4), (int) (p[1] / 4)), k -> new ArrayList<>()).add(i);
		}
		int[] bestUp = new int[N], bestDown = new int[N];
		for (int i = 0; i < N; i++) {
			float[] p = P.get(i);
			bestUp[i] = nearest(grid, p, (int) p[2] + 1);
			bestDown[i] = nearest(grid, p, (int) p[2] - 1);
		}

		// in-focus plane per point
		boolean[] kept = new boolean[N], broadPt = new boolean[N];
		double[] zPk = new double[N];
		inFocus(kept, broadPt, zPk, grid);

		// 3D: crossing zones
		boolean[] noZ = new boolean[N];
		int[] zoneOf = new int[N];
		Arrays.fill(zoneOf, -1);
		boolean[] isOv = new boolean[span.size()];
		int[] siteOf = null;
		if (threeD)
			siteOf = zones(noZ, zoneOf, isOv);

		if (rs.copyRule)
			copies(kept, broadPt, zoneOf, grid);

		// clean-up rules on the kept stretches, before any join
		final Set<Integer> cutAfter = new HashSet<>();
		final List<int[][]> lateAp = new ArrayList<>();
		if (rules.any())
			lateRules(kept, cutAfter, lateAp);

		// joining: consecutive points of a line, then through z
		par = new int[N];
		for (int i = 0; i < N; i++)
			par[i] = i;
		Map<Long, Integer> idx = new HashMap<>();
		for (int i = 0; i < N; i++)
			idx.put(pkey(P.get(i)), i);
		final double linkD = Math.sqrt(w * w + a * a);
		List<int[]> zonePerm = new ArrayList<>();
		for (int i = 0; i < N; i++)
			if (!cutAfter.contains(i)) {
				float[] p = P.get(i);
				Integer j = idx.get(pkey(p) + 1);
				if (j != null && near3(p, P.get(j), zPk[i], zPk[j], linkD)) {
					if (threeD && zoneOf[i] >= 0 && zoneOf[j] >= 0)
						zonePerm.add(new int[] { i, j });
					else
						union(i, j);
				}
			}
		if (!lateAp.isEmpty()) { // the rules' pairs: the roots of all kept stretches of one line apart from
									// those of the other
			forb = new HashMap<>();
			forbPar = new HashMap<>();
			for (int[][] l : lateAp) {
				Set<Integer> ra = new HashSet<>(), rb = new HashSet<>();
				for (int i : l[0])
					ra.add(find(i));
				for (int i : l[1])
					rb.add(find(i));
				Map<Integer, Set<Integer>> m = l[2][0] == RidgeCleanup.PARALLEL ? forbPar : forb;
				for (int x : ra)
					for (int y : rb)
						if (x != y) {
							m.computeIfAbsent(x, k -> new HashSet<>()).add(y);
							m.computeIfAbsent(y, k -> new HashSet<>()).add(x);
						}
			}
		}
		List<int[]> perm = new ArrayList<>(zonePerm), rejL = new ArrayList<>();
		zLinks(kept, noZ, bestUp, bestDown, zPk, grid, linkD, perm, rejL);
		if (threeD) {
			Map<Integer, List<Integer>> siteArms = pairZones(isOv, siteOf, zoneOf, zPk);
			seedMerge(grid, perm, rejL, siteArms, zPk);
		}
		shortPieces(kept, zPk); // step 6, last

		// objects: minimum length on the 3D object, physical painting, pieces joined
		Map<Integer, double[]> obj = new HashMap<>();
		for (int i = 0; i < N; i++)
			if (kept[i]) {
				int r = find(i);
				double[] o = obj.computeIfAbsent(r, k -> new double[] { 0, 1e9, -1e9 });
				float[] p = P.get(i);
				o[0] += 1.0;
				o[1] = Math.min(o[1], p[2]);
				o[2] = Math.max(o[2], p[2]);
			}
		Map<Integer, Integer> lab = new HashMap<>();
		int[][] out = new int[D][W * H];
		List<float[]> keptPts = new ArrayList<>();
		for (int i = 0; i < N; i++)
			if (kept[i]) {
				int r = find(i);
				double[] o = obj.get(r);
				double L3 = Math.sqrt(o[0] * o[0] + Math.pow(Math.max(0, o[2] - o[1] - Z_DISC) * a, 2));
				if (L3 < rs.minLength)
					continue;
				int id = lab.computeIfAbsent(r, k -> lab.size() + 1);
				float[] p = P.get(i);
				paintPhys(out, p[0], p[1], p[2], 1.0, id, false);
				keptPts.add(p);
			}
		bridge(out, keptPts, 1.0);
		return out;
	}

	/**
	 * Step 6 on the finished objects (RidgeShortPieces): each kept point at its
	 * in-focus z, scaled by the voxel size, with one plane (Z_DISC) of tolerance,
	 * so a standing cilium reaches far in z and is never a short piece. Joins
	 * override the pairs kept apart; dropped pieces lose their points.
	 */
	void shortPieces(boolean[] kept, double[] zPk) {
		final int mode = rs.shortPiecesMode();
		if (mode == RidgeShortPieces.APART)
			return;
		java.util.TreeMap<Integer, List<float[]>> obj = new java.util.TreeMap<>();
		Map<Integer, double[]> len = new HashMap<>();
		for (int i = 0; i < P.size(); i++)
			if (kept[i]) {
				float[] p = P.get(i);
				int r = find(i);
				obj.computeIfAbsent(r, k -> new ArrayList<>()).add(new float[] { p[0], p[1], (float) (zPk[i] * a) });
				double[] o = len.computeIfAbsent(r, k -> new double[] { 0, 1e9, -1e9 });
				o[0] += 1.0;
				o[1] = Math.min(o[1], p[2]);
				o[2] = Math.max(o[2], p[2]);
			}
		Set<Integer> long3D = new HashSet<>(); // the objects the minimum length keeps (as in run): only these take
												// pieces
		for (Map.Entry<Integer, double[]> e : len.entrySet()) {
			double[] o = e.getValue();
			if (Math.sqrt(o[0] * o[0] + Math.pow(Math.max(0, o[2] - o[1] - Z_DISC) * a, 2)) >= rs.minLength)
				long3D.add(e.getKey());
		}
		java.util.TreeMap<Integer, Integer> pieces = RidgeShortPieces.find(obj, long3D, w, Z_DISC * a);
		if (mode == RidgeShortPieces.DROP) {
			for (int i = 0; i < P.size(); i++)
				if (kept[i] && pieces.containsKey(find(i)))
					kept[i] = false;
			return;
		}
		for (Map.Entry<Integer, Integer> e : pieces.entrySet()) {
			int r = find(e.getKey()), t = find(e.getValue());
			if (r == t)
				continue;
			int n = Math.min(r, t), o = Math.max(r, t);
			par[o] = n;
			mergeForb(forb, n, o);
			mergeForb(forbPar, n, o);
		}
	}

	/**
	 * Decide each point's in-focus plane and which points to keep (class doc step 2). For every point it climbs the
	 * z intensity profile at its xy position to the peak and refines it to sub-plane accuracy (zPk). Points on flat
	 * lines (>= FLAT_LENGTH widths) use the local-variance profile instead - the sharpest plane, not the brightest.
	 * A point whose half-maximum run is broader than BROAD x the typical run is marked broadPt and kept across that
	 * run (a steep or standing filament). Otherwise a point is kept unless an aligned detection lies in a plane
	 * nearer its peak.
	 *
	 * @param kept    written true for points to keep
	 * @param broadPt written true for points of broad (steep/standing) profiles
	 * @param zPk     written with each point's sub-plane peak position
	 * @param grid    spatial index of all points, by (plane, x/4, y/4)
	 */
	void inFocus(boolean[] kept, boolean[] broadPt, double[] zPk, Map<Long, List<Integer>> grid) {
		final int N = P.size();
		float[][] S = new float[D][];
		for (int z = 0; z < D; z++) {
			FloatProcessor fp = (FloatProcessor) imp.getStack().getProcessor(z + 1).convertToFloat().duplicate();
			fp.blurGaussian(rs.effectiveZProfileSmoothing());
			S[z] = (float[]) fp.getPixels();
		}
		int[] run = new int[N];
		boolean[] inRun = new boolean[N];
		double[] f = new double[D];
		for (int i = 0; i < N; i++) {
			float[] p = P.get(i);
			int z = (int) p[2];
			for (int k = 0; k < D; k++)
				f[k] = Math.max(1e-3, bilin(S[k], p[0], p[1]));
			int m = climb(f, z);
			zPk[i] = refine(f, m);
			int lo = m, hi = m;
			while (lo > 0 && f[lo - 1] >= 0.5 * f[m])
				lo--;
			while (hi < D - 1 && f[hi + 1] >= 0.5 * f[m])
				hi++;
			run[i] = hi - lo + 1;
			inRun[i] = z >= lo && z <= hi;
		}
		S = null;
		// where the line lies flat, the sharpest plane (local variance, window half a
		// line width) instead of the brightest
		final double sFlat = w / 2, flatL = FLAT_LENGTH * w;
		float[][] V = new float[D][];
		for (int z = 0; z < D; z++) {
			FloatProcessor fp = (FloatProcessor) imp.getStack().getProcessor(z + 1).convertToFloat().duplicate();
			FloatProcessor sq = (FloatProcessor) fp.duplicate();
			float[] q = (float[]) sq.getPixels();
			for (int i = 0; i < q.length; i++)
				q[i] *= q[i];
			sq.blurGaussian(sFlat);
			fp.blurGaussian(sFlat);
			float[] m = (float[]) fp.getPixels();
			for (int i = 0; i < q.length; i++)
				q[i] = Math.max(1e-3f, q[i] - m[i] * m[i]);
			V[z] = q;
		}
		for (int i = 0; i < N; i++) {
			if (P.get(i)[8] < flatL)
				continue;
			float[] p = P.get(i);
			int z = (int) p[2];
			for (int k = 0; k < D; k++)
				f[k] = Math.max(1e-3, bilin(V[k], p[0], p[1]));
			zPk[i] = refine(f, climb(f, z));
		}
		V = null;
		// the typical z extent of an in-focus structure: the median half-maximum run on
		// long lines (all points if there are none)
		List<Integer> rl = new ArrayList<>();
		for (int i = 0; i < N; i++)
			if (P.get(i)[8] >= 3 * w)
				rl.add(run[i]);
		if (rl.isEmpty())
			for (int i = 0; i < N; i++)
				rl.add(run[i]);
		Collections.sort(rl);
		double medRun = rl.isEmpty() ? 1 : rl.get(rl.size() / 2);
		for (int i = 0; i < N; i++) {
			float[] p = P.get(i);
			int z = (int) p[2];
			if (run[i] > BROAD * medRun) {
				// steep or standing: keep its half-maximum run
				kept[i] = inRun[i];
				broadPt[i] = true;
				continue;
			}
			double dz = Math.abs(z - zPk[i]);
			if (dz <= 0.5) {
				kept[i] = true;
				continue;
			}
			int zt = (int) Math.round(zPk[i]), step = zt > z ? 1 : -1;
			boolean closer = false; // off peak: dropped only if an aligned detection lies closer to the peak
			for (int z2 = z + step; !closer && z2 != zt + step; z2 += step)
				if (Math.abs(z2 - zPk[i]) < dz)
					closer = nearest(grid, p, z2) >= 0;
			kept[i] = !closer;
		}
	}

	/**
	 * sub-plane position of the peak m: a parabola through the logarithms of its
	 * neighbours, within half a plane
	 */
	static double refine(double[] f, int m) {
		double zs = m;
		if (m > 0 && m < f.length - 1) {
			double l0 = Math.log(f[m - 1]), l1 = Math.log(f[m]), l2 = Math.log(f[m + 1]), c = l0 - 2 * l1 + l2;
			if (c < 0)
				zs = m + 0.5 * (l0 - l2) / c;
			zs = Math.max(m - 0.5, Math.min(m + 0.5, zs));
		}
		return zs;
	}

	/**
	 * uphill to the peak this plane belongs to; a local maximum joins a higher one
	 * across a shallow dip (a shoulder), or when it is a faint tail or an axial
	 * side lobe of a much brighter structure close by
	 */
	static int climb(double[] f, int z) {
		int n = f.length, m = z;
		for (;;) {
			for (;;) {
				int nx = m;
				if (m > 0 && f[m - 1] > f[nx])
					nx = m - 1;
				if (m < n - 1 && f[m + 1] > f[nx])
					nx = m + 1;
				if (nx == m)
					break;
				m = nx;
			}
			int best = -1;
			for (int dir = -1; dir <= 1 && best < 0; dir += 2) {
				double lo = f[m];
				int kM = -1;
				for (int k = m + dir; k >= 0 && k < n; k += dir) {
					lo = Math.min(lo, f[k]);
					if (f[k] > f[m] && lo >= PROFILE_DIP * f[m]) {
						best = k;
						break;
					}
					if (kM < 0 || f[k] > f[kM])
						kM = k;
				}
				if (best < 0 && kM >= 0 && f[kM] > f[m] && f[m] < PROFILE_TAU * f[kM]) {
					int lo2 = kM, hi2 = kM;
					while (lo2 > 0 && f[lo2 - 1] >= 0.5 * f[kM])
						lo2--;
					while (hi2 < n - 1 && f[hi2 + 1] >= 0.5 * f[kM])
						hi2++;
					if (Math.abs(m - kM) <= PROFILE_LOBES * (hi2 - lo2 + 1))
						best = kM;
				}
			}
			if (best < 0)
				return m;
			m = best;
		}
	}

	/**
	 * Copy rule (class doc step 3): drop out-of-focus copies. A kept point is dropped when a kept point at least
	 * copyRatio x brighter lies in another plane (up to COPY_PLANES away), within COPY_REACH widths, aligned within
	 * 30 deg, on a piece at least 3 widths long. The halo pass then drops a whole short piece (<= HALO_LENGTH widths)
	 * when every one of its points has such a brighter partner on a longer piece. Broad points and zone points are
	 * exempt.
	 */
	void copies(boolean[] kept, boolean[] broadPt, int[] zoneOf, Map<Long, List<Integer>> grid) {
		final int N = P.size();
		final double cD = COPY_REACH * w, ratio = rs.copyRatio;
		final int cDz = COPY_PLANES, gr = (int) Math.ceil(cD / 4) + 1;
		float[][] I = new float[D][];
		for (int z = 0; z < D; z++) {
			FloatProcessor fp = (FloatProcessor) imp.getStack().getProcessor(z + 1).convertToFloat().duplicate();
			fp.blurGaussian(rs.sigma);
			I[z] = (float[]) fp.getPixels();
		}
		float[] br = new float[N];
		for (int i = 0; i < N; i++) {
			float[] p = P.get(i);
			br[i] = I[(int) p[2]][clampY(Math.round(p[1])) * W + clampX(Math.round(p[0]))];
		}
		I = null;
		boolean[] drop = new boolean[N];
		for (int i = 0; i < N; i++) {
			if (!kept[i] || broadPt[i] || zoneOf[i] >= 0)
				continue;
			float[] p = P.get(i);
			for (int dz = -cDz; dz <= cDz && !drop[i]; dz++) {
				if (dz == 0)
					continue;
				int z2 = (int) p[2] + dz;
				if (z2 < 0 || z2 >= D)
					continue;
				for (int gx = -gr; gx <= gr && !drop[i]; gx++)
					for (int gy = -gr; gy <= gr && !drop[i]; gy++)
						for (int j : grid.getOrDefault(key(z2, (int) (p[0] / 4) + gx, (int) (p[1] / 4) + gy),
								Collections.<Integer>emptyList())) {
							float[] q = P.get(j);
							if (!kept[j] || q[8] < 3 * w || br[j] < ratio * br[i]
									|| Math.abs(q[4] * p[4] + q[5] * p[5]) < COS30
									|| Math.hypot(q[0] - p[0], q[1] - p[1]) > cD)
								continue;
							drop[i] = true;
							break;
						}
			}
		}
		// halo: a short piece whose every kept point has a much brighter partner on a longer piece in another plane
		final int hLen = (int) Math.round(HALO_LENGTH * w);
		Map<Integer, List<Integer>> byPiece = new HashMap<>();
		for (int i = 0; i < N; i++)
			if (kept[i] && !drop[i] && !broadPt[i] && P.get(i)[8] <= hLen)
				byPiece.computeIfAbsent((int) P.get(i)[6], k -> new ArrayList<>()).add(i);
		for (List<Integer> pc : byPiece.values()) {
			boolean all = true;
			for (int i : pc) {
				float[] p = P.get(i);
				boolean ok = false;
				for (int dz = -cDz; dz <= cDz && !ok; dz++) {
					if (dz == 0)
						continue;
					int z2 = (int) p[2] + dz;
					if (z2 < 0 || z2 >= D)
						continue;
					for (int gx = -gr; gx <= gr && !ok; gx++)
						for (int gy = -gr; gy <= gr && !ok; gy++)
							for (int j : grid.getOrDefault(key(z2, (int) (p[0] / 4) + gx, (int) (p[1] / 4) + gy),
									Collections.<Integer>emptyList())) {
								float[] q = P.get(j);
								if (!kept[j] || drop[j] || q[8] <= hLen || br[j] < ratio * br[i]
										|| Math.hypot(q[0] - p[0], q[1] - p[1]) > cD)
									continue;
								ok = true;
								break;
							}
				}
				if (!ok) {
					all = false;
					break;
				}
			}
			if (all)
				for (int i : pc)
					drop[i] = true;
		}
		for (int i = 0; i < N; i++)
			if (drop[i])
				kept[i] = false;
	}

	// ------------------------------------------------------------------ clean-up
	// rules on the kept stretches

	void lateRules(boolean[] kept, Set<Integer> cutAfter, List<int[][]> lateAp) {
		List<List<float[][]>> sLSZ = new ArrayList<>();
		List<List<int[]>> sApZ = new ArrayList<>();
		for (int z = 0; z < D; z++) {
			sLSZ.add(new ArrayList<>());
			sApZ.add(new ArrayList<>());
		}
		for (int[] sp : span) {
			int k = sp[0], e = sp[0] + sp[1];
			while (k < e) {
				if (!kept[k]) {
					k++;
					continue;
				}
				int k1 = k;
				while (k1 + 1 < e && kept[k1 + 1])
					k1++;
				int n = k1 - k + 1, z = (int) P.get(k)[2];
				float[][] st = new float[4][n];
				for (int q = 0; q < n; q++) {
					float[] p = P.get(k + q);
					st[0][q] = p[0];
					st[1][q] = p[1];
					st[2][q] = p[3];
					st[3][q] = k + q;
				}
				sLSZ.get(z).add(st);
				k = k1 + 1;
			}
		}
		final boolean twoDLines = slopeSeq.size() == D; // 3D: branch and crossing run below, on the lines as 2D sees
														// them
		rules.runLineRules = !twoDLines;
		rules.runOtherRules = true;
		for (int z = 0; z < D; z++) {
			// a kink cut leaves two kept stretches of one line adjacent in P: record the cut point
			
			List<float[][]> in = sLSZ.get(z);
			rules.setPlane(imp.getStack().getProcessor(z + 1));
			List<float[][]> outL = rules.apply(in, w, sApZ.get(z));
			sLSZ.set(z, outL);
			Set<Integer> present = new HashSet<>();
			for (float[][] L : outL)
				for (float f : L[3])
					present.add((int) f);
			for (float[][] L : outL) {
				int last = (int) L[3][L[3].length - 1];
				if (present.contains(last + 1) && P.get(last)[6] == P.get(last + 1)[6]) {
					boolean starts = false;
					for (float[][] M : outL)
						if ((int) M[3][0] == last + 1)
							starts = true;
					if (starts)
						cutAfter.add(last);
				}
			}
		}
		rules.dropHairpins(sLSZ, sApZ, w);
		for (int z = 0; z < D; z++)
			for (int[] dp : sApZ.get(z)) {
				float[] ra = sLSZ.get(z).get(dp[0])[3], rb = sLSZ.get(z).get(dp[1])[3];
				int[] ia = new int[ra.length], ib = new int[rb.length];
				for (int q = 0; q < ra.length; q++)
					ia[q] = (int) ra[q];
				for (int q = 0; q < rb.length; q++)
					ib[q] = (int) rb[q];
				lateAp.add(new int[][] { ia, ib, { dp[2] } });
			}
		if (twoDLines) {
			// branch and crossing on the kept stretches of the 2D lines; a point both lines
			// share binds neither; soft at junctions
			rules.runLineRules = true;
			rules.runOtherRules = false;
			for (int z = 0; z < D; z++) {
				// 3D branch/crossing pairs are recorded as soft (like parallel pairs): they do not bind the junction pairing
				List<float[][]> in = new ArrayList<>();
				for (int[] sq : slopeSeq.get(z)) {
					int k = 0;
					while (k < sq.length) {
						if (!kept[sq[k]]) {
							k++;
							continue;
						}
						int k1 = k;
						while (k1 + 1 < sq.length && kept[sq[k1 + 1]])
							k1++;
						int n = k1 - k + 1;
						float[][] st = new float[4][n];
						for (int q = 0; q < n; q++) {
							float[] p = P.get(sq[k + q]);
							st[0][q] = p[0];
							st[1][q] = p[1];
							st[2][q] = p[3];
							st[3][q] = sq[k + q];
						}
						in.add(st);
						k = k1 + 1;
					}
				}
				List<int[]> ap = new ArrayList<>();
				List<float[][]> outL = rules.apply(in, w, ap);
				for (int[] dp : ap) {
					Set<Integer> sa = new java.util.LinkedHashSet<>(), sb = new java.util.LinkedHashSet<>();
					for (float f : outL.get(dp[0])[3])
						sa.add((int) f);
					for (float f : outL.get(dp[1])[3])
						sb.add((int) f);
					Set<Integer> both = new HashSet<>(sa);
					both.retainAll(sb);
					sa.removeAll(both);
					sb.removeAll(both);
					if (sa.isEmpty() || sb.isEmpty())
						continue;
					lateAp.add(new int[][] { sa.stream().mapToInt(Integer::intValue).toArray(),
							sb.stream().mapToInt(Integer::intValue).toArray(), { RidgeCleanup.PARALLEL } });
				}
			}
			rules.runOtherRules = true;
		}
	}

	/**
	 * 3D: the plane detected as 2D does (with overlap resolution), each line mapped
	 * onto this plane's points (nearest within 1 px)
	 */
	List<int[]> lines2D(ImageProcessor ip, int pz0) {
		Lines lsS = new LineDetector().setLineWidth(w).detectLines(ip, rs.sigma, rs.upperThreshold, rs.lowerThreshold,
				MIN_PIECE, rs.maxLength, rs.darkLine, rs.correctPosition, rs.estimateWidth, rs.extendLine,
				OverlapOption.SLOPE);
		Map<Long, List<Integer>> g = new HashMap<>();
		for (int i = pz0; i < P.size(); i++) {
			float[] p = P.get(i);
			g.computeIfAbsent(((long) Math.floor(p[0] / 2) << 32) | ((long) Math.floor(p[1] / 2) & 0xffffffffL),
					k -> new ArrayList<>()).add(i);
		}
		List<int[]> seqs = new ArrayList<>();
		for (Line l : lsS) {
			float[] X = l.getXCoordinates(), Y = l.getYCoordinates();
			int[] sq = new int[X.length];
			int n = 0;
			for (int q = 0; q < X.length; q++) {
				int b = -1;
				double bd = 1.0;
				long cx0 = (long) Math.floor(X[q] / 2), cy0 = (long) Math.floor(Y[q] / 2);
				for (long cx = cx0 - 1; cx <= cx0 + 1; cx++)
					for (long cy = cy0 - 1; cy <= cy0 + 1; cy++)
						for (int i : g.getOrDefault((cx << 32) | (cy & 0xffffffffL),
								Collections.<Integer>emptyList())) {
							float[] p = P.get(i);
							double d = Math.hypot(p[0] - X[q], p[1] - Y[q]);
							if (d < bd) {
								bd = d;
								b = i;
							}
						}
				if (b >= 0 && (n == 0 || sq[n - 1] != b))
					sq[n++] = b;
			}
			if (n > 0)
				seqs.add(Arrays.copyOf(sq, n));
		}
		return seqs;
	}

	/**
	 * Links through z (class doc step 5). Pairs of points in neighbouring planes that are aligned (within 30 deg) and
	 * within linkD at their fitted depths are collected and joined best-first (linkCost: closest, best aligned). In
	 * 3D two pieces link only if they run along the same path where they overlap (within COHERENCE deg); a link that
	 * fails this is deferred to perm (permissive) and recorded in rejL (so seedMerge can keep two long tracks apart).
	 * Points in a crossing zone (noZ) only ever get permissive links.
	 */
	void zLinks(boolean[] kept, boolean[] noZ, int[] bestUp, int[] bestDown, double[] zPk,
			Map<Long, List<Integer>> grid, double linkD, List<int[]> perm, List<int[]> rejL) {
		final int N = P.size();
		final double coh = threeD ? COHERENCE : 0;
		final int minSt = (int) Math.ceil(w);
		List<int[]> links = new ArrayList<>();
		Map<Long, int[]> rng = new HashMap<>();
		for (int i = 0; i < N; i++) {
			float[] p = P.get(i);
			for (int dz = -1; dz <= 1; dz += 2) {
				int z2 = (int) p[2] + dz;
				if (z2 < 0 || z2 >= D)
					continue;
				for (int gx = -2; gx <= 2; gx++)
					for (int gy = -2; gy <= 2; gy++)
						for (int j : grid.getOrDefault(key(z2, (int) (p[0] / 4) + gx, (int) (p[1] / 4) + gy),
								Collections.<Integer>emptyList())) {
							float[] q = P.get(j);
							double dxy = Math.hypot(q[0] - p[0], q[1] - p[1]);
							if (dxy > 2 * tol)
								continue;
							if (coh > 0 && p[2] < q[2]) {
								long k = ((long) p[6] << 32) | (long) q[6];
								int[] r = rng.computeIfAbsent(k, kk -> new int[] { 1 << 30, -1, 1 << 30, -1 });
								r[0] = Math.min(r[0], (int) p[7]);
								r[1] = Math.max(r[1], (int) p[7]);
								r[2] = Math.min(r[2], (int) q[7]);
								r[3] = Math.max(r[3], (int) q[7]);
							}
							if (Math.abs(q[4] * p[4] + q[5] * p[5]) >= COS30 && near3(p, q, zPk[i], zPk[j], linkD)) {
								if (threeD && (noZ[i] || noZ[j])) {
									// in a crossing zone: permissive only
									if (p[2] < q[2])
										perm.add(new int[] { i, j });
									continue;
								}
								links.add(coh > 0 ? (p[2] < q[2] ? new int[] { i, j } : new int[] { j, i })
										: new int[] { i, j });
							}
						}
			}
		}
		links.sort(Comparator.comparingDouble(l -> linkCost(P.get(l[0]), P.get(l[1]))));
		
		if (coh <= 0) {
			for (int[] l : links)
				union(l[0], l[1]);
			return;
		}
		// 3D: two pieces in neighbouring planes link only if they run along the same
		// path where they lie together (within COHERENCE deg)
		final double cc = Math.cos(Math.toRadians(coh));
		Map<Long, Boolean> ok = new HashMap<>();
		for (int[] l : links) {
			float[] p = P.get(l[0]), q = P.get(l[1]);
			long k = ((long) p[6] << 32) | (long) q[6];
			Boolean b = ok.get(k);
			if (b == null) {
				int[] r = rng.get(k);
				int sA = span.get((int) p[6])[0], sB = span.get((int) q[6])[0];
				if (r == null || Math.min(r[1] - r[0], r[3] - r[2]) + 1 < minSt)
					b = true;
				else {
					float[] a0 = P.get(sA + r[0]), a1 = P.get(sA + r[1]), b0 = P.get(sB + r[2]), b1 = P.get(sB + r[3]);
					double ux = a1[0] - a0[0], uy = a1[1] - a0[1], vx = b1[0] - b0[0], vy = b1[1] - b0[1],
							nu = Math.hypot(ux, uy), nv = Math.hypot(vx, vy);
					b = nu == 0 || nv == 0 || Math.abs(ux * vx + uy * vy) / (nu * nv) >= cc;
				}
				ok.put(k, b);
			}
			if (b)
				union(l[0], l[1]);
			else {
				perm.add(l);
				rejL.add(l);
			}
		}
	}


	/**
	 * 3D crossing zones (class doc step 6). Junctions lying together (within one width, across planes) are merged by
	 * union-find into crossing SITES; short overlap pieces between two such junctions are flagged (isOv). Every point
	 * within XR widths of a site is assigned that site's zone (zoneOf) and marked noZ, so links through z are not
	 * trusted inside a crossing.
	 *
	 * @param noZ    written true for points inside any crossing zone
	 * @param zoneOf written with each point's site id, or -1
	 * @param isOv   written true for each span that is an overlap piece
	 * @return siteOf: union-find of junctions into sites (by junction index)
	 */
	int[] zones(boolean[] noZ, int[] zoneOf, boolean[] isOv) {
		final int N = P.size(), nJ = J.size();
		final double ovMax = OVERLAP * w, rx = XR * w;
		int[] longEnds = new int[nJ];
		for (int[] s : span)
			if (s[1] >= 2 * w) {
				if (s[2] >= 0)
					longEnds[s[2]]++;
				if (s[3] >= 0)
					longEnds[s[3]]++;
			}
		for (int L = 0; L < span.size(); L++) {
			int[] s = span.get(L);
			if (s[2] < 0 || s[3] < 0 || s[2] == s[3] || s[1] > ovMax)
				continue; // an overlap piece: between two junctions,
			int own = s[1] >= 2 * w ? 1 : 0;
			
			// an overlap piece: short, between two different junctions, with two long branches at each end
			if (longEnds[s[2]] - own >= 2 && longEnds[s[3]] - own >= 2)
				isOv[L] = true;
		}

		int[] siteOf = new int[nJ];
		for (int j = 0; j < nJ; j++)
			siteOf[j] = j;
		final int cw = Math.max(1, (int) Math.ceil(w));
		Map<Long, List<Integer>> g2 = new HashMap<>();
		for (int j = 0; j < nJ; j++) {
			float[] q = J.get(j);
			g2.computeIfAbsent(key((int) q[2], (int) (q[0] / cw), (int) (q[1] / cw)), k -> new ArrayList<>()).add(j);
		}
		for (int j = 0; j < nJ; j++) {
			float[] q = J.get(j);
			for (int dz = 0; dz <= 1; dz++)
				for (int gx = -1; gx <= 1; gx++)
					for (int gy = -1; gy <= 1; gy++)
						for (int k : g2.getOrDefault(
								key((int) q[2] + dz, (int) (q[0] / cw) + gx, (int) (q[1] / cw) + gy),
								Collections.<Integer>emptyList())) {
							if (k == j)
								continue;
							float[] r = J.get(k);
							if (Math.hypot(r[0] - q[0], r[1] - q[1]) <= w)
								unionIn(siteOf, j, k);
						}
		}
		for (int L = 0; L < span.size(); L++)
			if (isOv[L])
				unionIn(siteOf, span.get(L)[2], span.get(L)[3]);
		List<float[]> ze = new ArrayList<>();
		for (int j = 0; j < nJ; j++) {
			float[] q = J.get(j);
			ze.add(new float[] { q[0], q[1], q[2], findIn(siteOf, j) });
		}
		for (int L = 0; L < span.size(); L++)
			if (isOv[L]) {
				int[] s = span.get(L);
				int st = findIn(siteOf, s[2]);
				for (int k = 0; k < s[1]; k++) {
					float[] p = P.get(s[0] + k);
					ze.add(new float[] { p[0], p[1], p[2], st });
				}
			}
		final int cr = Math.max(1, (int) Math.ceil(rx));
		Map<Long, List<Integer>> zg = new HashMap<>();
		for (int e = 0; e < ze.size(); e++) {
			float[] q = ze.get(e);
			zg.computeIfAbsent(key((int) q[2], (int) (q[0] / cr), (int) (q[1] / cr)), k -> new ArrayList<>()).add(e);
		}
		for (int i = 0; i < N; i++) {
			float[] p = P.get(i);
			double bd = Double.MAX_VALUE;
			for (int dz = -1; dz <= 1; dz++)
				for (int gx = -1; gx <= 1; gx++)
					for (int gy = -1; gy <= 1; gy++)
						for (int e : zg.getOrDefault(
								key((int) p[2] + dz, (int) (p[0] / cr) + gx, (int) (p[1] / cr) + gy),
								Collections.<Integer>emptyList())) {
							float[] q = ze.get(e);
							double d = Math.hypot(q[0] - p[0], q[1] - p[1]);
							if (d <= rx && d < bd) {
								bd = d;
								zoneOf[i] = (int) q[3];
							}
						}
			if (zoneOf[i] >= 0)
				noZ[i] = true;
		}
		for (int L = 0; L < span.size(); L++)
			if (isOv[L]) {
				int[] s = span.get(L);
				for (int k = 0; k < s[1]; k++)
					noZ[s[0] + k] = true;
			}
		return siteOf;
	}

	// ------------------------------------------------------------------ 3D:
	// pairing the endings at each crossing site

	Map<Integer, List<Integer>> pairZones(boolean[] isOv, int[] siteOf, int[] zoneOf, double[] zPk) {
		final int M = Math.max(4, (int) Math.round(FIT * w));
		final double c0 = UNPAIRED * w, cosBend = Math.cos(Math.toRadians(BEND));
		List<int[]> armK = new ArrayList<>();
		List<List<double[]>> armF = new ArrayList<>();
		List<double[]> armD = new ArrayList<>();
		Map<Long, List<Integer>> armsOf = new LinkedHashMap<>();
		List<int[]> ends = new ArrayList<>(); // {site, piece, index of the last point inside the zone (or the end),
												// direction away}
		for (int L = 0; L < span.size(); L++) {
			int[] s = span.get(L);
			if (s[1] < 2 || isOv[L])
				continue;
			for (int k = 0; k < s[1]; k++) {
				int i = s[0] + k, z = zoneOf[i];
				if (z < 0)
					continue;
				if (k + 1 < s[1] && zoneOf[i + 1] != z)
					ends.add(new int[] { z, L, k, +1 });
				if (k > 0 && zoneOf[i - 1] != z)
					ends.add(new int[] { z, L, k, -1 });
			}
			for (int side = 0; side < 2; side++) {
				int j = s[2 + side];
				if (j < 0)
					continue;
				int st = findIn(siteOf, j), k = side == 0 ? 0 : s[1] - 1;
				if (zoneOf[s[0] + k] != st)
					ends.add(new int[] { st, L, k, side == 0 ? +1 : -1 });
			}
		}
		for (int[] en : ends) {
			int site = en[0];
			int[] s = span.get(en[1]);
			int step = en[3];
			List<double[]> fp = new ArrayList<>();
			int ie = -1;
			for (int k = en[2]; k >= 0 && k < s[1] && fp.size() < M; k += step) {
				int i = s[0] + k;
				if (zoneOf[i] == site)
					continue;
				if (ie < 0)
					ie = i;
				float[] p = P.get(i);
				fp.add(new double[] { p[0], p[1], zPk[i] * a });
			}
			if (fp.size() < 3)
				continue;
			double[] d = { fp.get(0)[0] - fp.get(fp.size() - 1)[0], fp.get(0)[1] - fp.get(fp.size() - 1)[1],
					fp.get(0)[2] - fp.get(fp.size() - 1)[2] };
			double dn = norm(d);
			if (dn == 0)
				continue;
			for (int c = 0; c < 3; c++)
				d[c] /= dn;
			int tr = find(ie);
			List<Integer> lst = armsOf.computeIfAbsent(((long) site << 32) | tr, k -> new ArrayList<>());
			int hit = -1;
			for (int m : lst)
				if (dot(armD.get(m), d) > 0.5 * norm(armD.get(m))) {
					hit = m;
					break;
				}
			if (hit < 0) {
				hit = armK.size();
				armK.add(new int[] { site, ie });
				armF.add(new ArrayList<>());
				armD.add(new double[3]);
				lst.add(hit);
			}
			longPiece.merge(armK.get(hit)[1], s[1], Math::max);
			armReach.merge(armK.get(hit)[1], fp.size(), Math::max);
			armF.get(hit).addAll(fp);
			double[] ad = armD.get(hit);
			ad[0] += d[0];
			ad[1] += d[1];
			ad[2] += d[2];
		}
		int nA = armK.size();
		double[][] cen = new double[nA][3], dir = new double[nA][];
		for (int m = 0; m < nA; m++) {
			for (double[] q : armF.get(m))
				for (int c = 0; c < 3; c++)
					cen[m][c] += q[c] / armF.get(m).size();
			double[] d = armD.get(m).clone();
			double n = norm(d);
			for (int c = 0; c < 3; c++)
				d[c] /= n;
			dir[m] = d;
			armDirOf.put(armK.get(m)[1], d);
		}
		boolean[] used = new boolean[nA];
		List<double[]> thru = new ArrayList<>();
		for (List<Integer> lst : armsOf.values())
			if (lst.size() >= 2) {
				for (int m : lst)
					used[m] = true;
				double[] d = dir[lst.get(0)];
				thru.add(new double[] { armK.get(lst.get(0))[0], armK.get(lst.get(0))[1], -d[0], -d[1], -d[2] });
			}
		Map<Integer, List<Integer>> bySite = new TreeMap<>();
		for (int m = 0; m < nA; m++)
			if (!used[m])
				bySite.computeIfAbsent(armK.get(m)[0], k -> new ArrayList<>()).add(m);
		for (List<Integer> lst : bySite.values()) {
			int n = lst.size();
			if (n < 2)
				continue;
			double[][] C = new double[n][n];
			for (int x = 0; x < n; x++)
				for (int y = 0; y < n; y++) {
					if (x == y) {
						C[x][y] = Double.MAX_VALUE;
						continue;
					}
					int A = lst.get(x), B = lst.get(y);
					int ra = find(armK.get(A)[1]), rb = find(armK.get(B)[1]);
					if (ra == rb || dot(dir[A], dir[B]) > -cosBend) {
						C[x][y] = Double.MAX_VALUE;
						continue;
					} // same track, or a bend above 60 deg
					if (forb != null) {
						Set<Integer> fb = forb.get(ra);
						if (fb != null && fb.contains(rb)) {
							C[x][y] = Double.MAX_VALUE;
							continue;
						}
					} // hard pairs only: soft ones do not bind the pairing
					double dAB = dev(cen[A], dir[A], armF.get(B)), dBA = dev(cen[B], dir[B], armF.get(A));
					C[x][y] = (dAB == Double.MAX_VALUE || dBA == Double.MAX_VALUE) ? Double.MAX_VALUE
							: 0.5 * (dAB + dBA);
				}
			int[] match = assign(C, c0);
			for (int x = 0; x < n; x++) {
				int y = match[x];
				if (y < 0 || y < x)
					continue;
				int A = lst.get(x), B = lst.get(y);
				int ra = find(armK.get(A)[1]), rb = find(armK.get(B)[1]);
				if (ra == rb)
					continue;
				unionAtJunction(ra, rb);
				used[A] = used[B] = true;
				thru.add(new double[] { armK.get(A)[0], armK.get(A)[1], -dir[A][0], -dir[A][1], -dir[A][2] });
			}
		}
		for (int L = 0; L < span.size(); L++)
			if (isOv[L]) {
				int[] s = span.get(L);
				int site = findIn(siteOf, s[2]);
				float[] p0 = P.get(s[0]), p1 = P.get(s[0] + s[1] - 1); // an overlap piece joins the most parallel path
																		// through its site
				double[] ax = { p1[0] - p0[0], p1[1] - p0[1], (zPk[s[0] + s[1] - 1] - zPk[s[0]]) * a };
				if (norm(ax) == 0)
					continue;
				double best = COS30;
				int bp = -1;
				for (double[] t : thru)
					if ((int) t[0] == site) {
						double c = Math.abs(dot(ax, new double[] { t[2], t[3], t[4] }))
								/ (norm(ax) * norm(new double[] { t[2], t[3], t[4] }));
						if (c > best) {
							best = c;
							bp = (int) t[1];
						}
					}
				if (bp >= 0)
					unionAtJunction(s[0], bp);
			}
		Map<Integer, List<Integer>> siteArms = new TreeMap<>();
		for (int m = 0; m < nA; m++)
			siteArms.computeIfAbsent(armK.get(m)[0], k -> new ArrayList<>()).add(armK.get(m)[1]);
		// a track running through a site's zone without ending there counts as two arms
		// of that site
		Map<Integer, Set<Integer>> armRoots = new HashMap<>();
		for (int m = 0; m < nA; m++)
			armRoots.computeIfAbsent(armK.get(m)[0], k -> new HashSet<>()).add(find(armK.get(m)[1]));
		Map<Integer, Map<Integer, Integer>> passing = new TreeMap<>();
		for (int i = 0; i < P.size(); i++) {
			int s = zoneOf[i];
			if (s < 0)
				continue;
			int r = find(i);
			if (armRoots.getOrDefault(s, Collections.<Integer>emptySet()).contains(r))
				continue;
			passing.computeIfAbsent(s, k -> new TreeMap<>()).putIfAbsent(r, i);
		}
		for (Map.Entry<Integer, Map<Integer, Integer>> e : passing.entrySet()) {
			List<Integer> l = siteArms.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
			for (int pt : e.getValue().values()) {
				l.add(pt);
				l.add(pt);
				float[] q = P.get(pt);
				armDirOf.putIfAbsent(pt, new double[] { q[4], q[5], 0 });
			}
		}
		return siteArms;
	}

	/**
	 * mean distance of points q from the line (c, u), counting only points ahead of
	 * c along u; MAX if none is ahead
	 */
	static double dev(double[] c, double[] u, List<double[]> q) {
		double s = 0;
		int n = 0;
		for (double[] p : q) {
			double[] v = { p[0] - c[0], p[1] - c[1], p[2] - c[2] };
			double t = dot(v, u);
			if (t <= 0)
				continue;
			double d2 = dot(v, v) - t * t;
			s += Math.sqrt(Math.max(0, d2));
			n++;
		}
		return n == 0 ? Double.MAX_VALUE : s / n;
	}

	/**
	 * minimum-cost pairing of n endings; C[x][y] = cost (MAX = not allowed); an
	 * unpaired ending costs c0. Exact up to 12, greedy above.
	 */
	static int[] assign(double[][] C, double c0) {
		int n = C.length;
		int[] best = new int[n], cur = new int[n];
		Arrays.fill(cur, -2);
		if (n <= 12) {
			double[] bc = { Double.MAX_VALUE };
			search(C, c0, cur, 0, 0, bc, best);
			return best;
		}
		Arrays.fill(best, -1);
		List<double[]> e = new ArrayList<>();
		for (int x = 0; x < n; x++)
			for (int y = x + 1; y < n; y++) {
				double c = Math.min(C[x][y], C[y][x]);
				if (c < 2 * c0)
					e.add(new double[] { c, x, y });
			}
		e.sort((p, q) -> p[0] != q[0] ? Double.compare(p[0], q[0])
				: p[1] != q[1] ? Double.compare(p[1], q[1]) : Double.compare(p[2], q[2]));
		for (double[] v : e) {
			int x = (int) v[1], y = (int) v[2];
			if (best[x] < 0 && best[y] < 0) {
				best[x] = y;
				best[y] = x;
			}
		}
		return best;
	}

	static void search(double[][] C, double c0, int[] cur, int k, double cost, double[] bc, int[] best) {
		int n = C.length;
		while (k < n && cur[k] != -2)
			k++;
		if (cost >= bc[0])
			return;
		if (k == n) {
			bc[0] = cost;
			System.arraycopy(cur, 0, best, 0, n);
			return;
		}
		cur[k] = -1;
		search(C, c0, cur, k + 1, cost + c0, bc, best);
		for (int y = k + 1; y < n; y++)
			if (cur[y] == -2) {
				double c = Math.min(C[k][y], C[y][k]);
				if (c >= 2 * c0)
					continue;
				cur[k] = y;
				cur[y] = k;
				search(C, c0, cur, k + 1, cost + c, bc, best);
				cur[y] = -2;
			}
		cur[k] = -2;
	}

	// ------------------------------------------------------------------ 3D: seed
	// merge

	/**
	 * the strict tracks after pairing are the filaments; two long ones may not
	 * merge where there is evidence that they differ (the pair rule rejected a link
	 * between them, or one passes through a site where other long ones arrive);
	 * then the permissive links merge everything else
	 */
	void seedMerge(Map<Long, List<Integer>> grid, List<int[]> perm, List<int[]> rejL,
			Map<Integer, List<Integer>> siteArms, double[] zPk) {
		final double longL = Math.max(rs.minLength, LONG * w);
		Map<Integer, Double> len = extent();
		standing(zPk, len);
		if (forb == null)
			forb = new HashMap<>();
		for (List<Integer> arms : siteArms.values()) {
			int nLong = 0;
			Set<Integer> roots = new TreeSet<>();
			for (int pt : arms) {
				int r = find(pt);
				if (len.get(r) >= longL || (len.get(r) > 0 && longPiece.getOrDefault(pt, 0) >= longL)) {
					nLong++;
					roots.add(r);
				}
			}
			Map<Integer, Integer> cnt = new TreeMap<>();
			for (int pt : arms) {
				int r = find(pt);
				if (roots.contains(r))
					cnt.merge(r, 1, Integer::sum);
			}
			Map<Integer, List<double[]>> dirs = new HashMap<>();
			for (int pt : arms) {
				int r = find(pt);
				double[] d = armDirOf.get(pt);
				if (d != null)
					dirs.computeIfAbsent(r, k -> new ArrayList<>()).add(d);
			}
			if (nLong >= 3 && roots.size() >= 2)
				for (int x : roots)
					if (cnt.get(x) >= 2)
						for (int y : roots)
							if (y != x) { // x passes through: apart from the other long ones,
								double best = 0;
								for (double[] u : dirs.getOrDefault(x, Collections.<double[]>emptyList()))
									for (double[] v : dirs.getOrDefault(y, Collections.<double[]>emptyList())) {
										double c = Math.abs(u[0] * v[0] + u[1] * v[1])
												/ Math.max(1e-9, Math.hypot(u[0], u[1]) * Math.hypot(v[0], v[1]));
										best = Math.max(best, c);
									}
								if (best > Math.cos(Math.toRadians(SITE_ANGLE)))
									continue; // unless an arm of y runs parallel to it (a piece of it)
								forb.computeIfAbsent(x, k -> new HashSet<>()).add(y);
								forb.computeIfAbsent(y, k -> new HashSet<>()).add(x);
							}
		}
		for (int[] l : rejL) {
			int x = find(l[0]), y = find(l[1]);
			if (x == y || len.get(x) < longL || len.get(y) < longL)
				continue; // two long tracks whose link the pair rule rejected
			forb.computeIfAbsent(x, k -> new HashSet<>()).add(y);
			forb.computeIfAbsent(y, k -> new HashSet<>()).add(x);
		}
		for (int[] l : perm)
			cunion(l[0], l[1], true);
	}

	/**
	 * standing tracks (z extent of their fitted peaks, in px, above their xy
	 * extent) are never long
	 */
	void standing(double[] zPk, Map<Integer, Double> len) {
		Map<Integer, double[]> zr = new HashMap<>();
		for (int i = 0; i < P.size(); i++) {
			double[] r = zr.computeIfAbsent(find(i), k -> new double[] { Double.MAX_VALUE, -Double.MAX_VALUE });
			r[0] = Math.min(r[0], zPk[i]);
			r[1] = Math.max(r[1], zPk[i]);
		}
		for (Map.Entry<Integer, double[]> e : zr.entrySet()) {
			double zx = (e.getValue()[1] - e.getValue()[0]) * a;
			if (zx > len.get(e.getKey()))
				len.put(e.getKey(), 0.0);
		}
	}

	/** xy extent (bounding-box diagonal) per track */
	Map<Integer, Double> extent() {
		Map<Integer, float[]> bb = new HashMap<>();
		for (int i = 0; i < P.size(); i++) {
			float[] p = P.get(i);
			float[] b = bb.computeIfAbsent(find(i), k -> new float[] { 1e9f, 1e9f, -1e9f, -1e9f });
			b[0] = Math.min(b[0], p[0]);
			b[1] = Math.min(b[1], p[1]);
			b[2] = Math.max(b[2], p[0]);
			b[3] = Math.max(b[3], p[1]);
		}
		Map<Integer, Double> e = new HashMap<>();
		for (Map.Entry<Integer, float[]> x : bb.entrySet()) {
			float[] b = x.getValue();
			e.put(x.getKey(), Math.hypot(b[2] - b[0], b[3] - b[1]));
		}
		return e;
	}

	// ------------------------------------------------------------------ 3D: the
	// tracer

	final List<int[]> tracedLS = new ArrayList<>();

	/**
	 * two filaments running side by side merge into one ridge at Steger's scale.
	 * Where two pieces arrive nearly parallel at a junction and a third leaves in
	 * their direction, go back one line width on each and trace each forward
	 * through the merged ridge on the plane at fine scale; stop where the traces
	 * meet, the dip between them is gone, or both leave the merged ridge.
	 */
	void traceMerges(List<float[][]> LS, Map<Integer, int[]> endJ, Map<Long, Integer> jAt, int z, float[] raw) {
		final double cosA = Math.cos(Math.toRadians(TRACE_ANGLE));
		FloatProcessor fp = new FloatProcessor(W, H, raw.clone());
		fp.blurGaussian(TRACE_SIGMA);
		float[] F = (float[]) fp.getPixels();
		final int kb = Math.max(2, (int) Math.round(w));
		Map<Long, List<int[]>> ends = new LinkedHashMap<>();
		for (int li = 0; li < LS.size(); li++) {
			float[] X = LS.get(li)[0], Y = LS.get(li)[1];
			int n = X.length;
			if (n < 2 * kb)
				continue;
			for (int side = 0; side < 2; side++) {
				int e = side == 0 ? 0 : n - 1;
				long k = fkey(X[e], Y[e]);
				if (jAt.containsKey(k))
					ends.computeIfAbsent(k, q -> new ArrayList<>()).add(new int[] { li, side });
			}
		}
		Set<Integer> touched = new HashSet<>();
		for (Map.Entry<Long, List<int[]>> en : ends.entrySet()) {
			List<int[]> E = en.getValue();
			if (E.size() < 3)
				continue;
			int nE = E.size();
			double[][] din = new double[nE][];
			for (int m = 0; m < nE; m++) {
				float[] X = LS.get(E.get(m)[0])[0], Y = LS.get(E.get(m)[0])[1];
				int n = X.length, e = E.get(m)[1] == 0 ? 0 : n - 1,
						b = E.get(m)[1] == 0 ? Math.min(n - 1, 2 * kb) : Math.max(0, n - 1 - 2 * kb);
				double dx = X[e] - X[b], dy = Y[e] - Y[b], dn = Math.hypot(dx, dy);
				din[m] = dn > 0 ? new double[] { dx / dn, dy / dn } : new double[] { 0, 0 };
			}
			int bA = -1, bB = -1, bC = -1;
			double bs = -2;
			for (int c = 0; c < nE; c++)
				for (int x = 0; x < nE; x++)
					for (int y = x + 1; y < nE; y++) {
						if (x == c || y == c)
							continue;
						double ab = din[x][0] * din[y][0] + din[x][1] * din[y][1],
								ac = -(din[x][0] * din[c][0] + din[x][1] * din[c][1]),
								bc = -(din[y][0] * din[c][0] + din[y][1] * din[c][1]);
						double sc = Math.min(ab, Math.min(ac, bc));
						if (ab >= cosA && ac >= cosA && bc >= cosA && sc > bs) {
							bs = sc;
							bA = x;
							bB = y;
							bC = c;
						}
					}
			if (bA < 0)
				continue;
			int lA = E.get(bA)[0], lB = E.get(bB)[0], lC = E.get(bC)[0];
			if (touched.contains(lA) || touched.contains(lB) || touched.contains(lC) || lA == lB || lA == lC
					|| lB == lC)
				continue;
			float[][] A = orient(LS.get(lA), E.get(bA)[1] == 0), B = orient(LS.get(lB), E.get(bB)[1] == 0),
					Cc = orient(LS.get(lC), E.get(bC)[1] == 1);
			int iA = A[0].length - 1 - kb, iB = B[0].length - 1 - kb;
			if (iA < 1 || iB < 1)
				continue;
			double[] pA = { A[0][iA], A[1][iA] }, pB = { B[0][iB], B[1][iB] }, dA = { din[bA][0], din[bA][1] },
					dB = { din[bB][0], din[bB][1] };
			List<double[]> tA = new ArrayList<>(), tB = new ArrayList<>();
			int steps = Cc[0].length + 2 * kb;
			for (int st = 0; st < steps; st++) {
				double[] nA2 = step(F, pA, dA), nB2 = step(F, pB, dB);
				if (nA2 == null || nB2 == null)
					break;
				if (Math.hypot(nA2[0] - nB2[0], nA2[1] - nB2[1]) < 2.0)
					break; // the traces meet: a true overlap
				double lower = Math.min(bilin(F, nA2[0], nA2[1]), bilin(F, nB2[0], nB2[1])), lo = Double.MAX_VALUE;
				for (int k = 1; k < 10; k++) {
					double t = k / 10.0;
					lo = Math.min(lo, bilin(F, nA2[0] + t * (nB2[0] - nA2[0]), nA2[1] + t * (nB2[1] - nA2[1])));
				}
				if (lo > TRACE_DIP * lower)
					break; // no dip between them any more
				if (distTo(Cc, nA2) > w && distTo(Cc, nB2) > w && st > 2 * kb)
					break; // both left the merged ridge
				updDir(dA, pA, nA2);
				updDir(dB, pB, nB2);
				pA = nA2;
				pB = nB2;
				tA.add(pA);
				tB.add(pB);
			}
			if (tA.size() < 2 * kb)
				continue;
			double mx = (pA[0] + pB[0]) / 2, my = (pA[1] + pB[1]) / 2;
			int kc = 0;
			double bd = Double.MAX_VALUE;
			for (int k = 0; k < Cc[0].length; k++) {
				double d = Math.hypot(Cc[0][k] - mx, Cc[1][k] - my);
				if (d < bd) {
					bd = d;
					kc = k;
				}
			}
			int jOld = jAt.get(en.getKey());
			J.set(jOld, new float[] { (float) mx, (float) my, z });
			LS.set(lA, join(A, iA, tA));
			LS.set(lB, join(B, iB, tB));
			LS.set(lC, tail(Cc, kc));
			tracedLS.add(new int[] { lA, lB });
			endJ.put(lA, new int[] { -2, jOld });
			endJ.put(lB, new int[] { -2, jOld });
			endJ.put(lC, new int[] { LS.get(lC)[0].length > 0 ? jOld : -1, -2 });
			touched.add(lA);
			touched.add(lB);
			touched.add(lC);
			nTraced++;
		}
	}

	static float[][] orient(float[][] L, boolean reverse) {
		if (!reverse)
			return L;
		int n = L[0].length;
		float[][] r = new float[3][n];
		for (int c = 0; c < 3; c++)
			for (int i = 0; i < n; i++)
				r[c][i] = L[c][n - 1 - i];
		return r;
	}

	static float[][] join(float[][] L, int upto, List<double[]> t) {
		int n = upto + 1 + t.size();
		float[][] r = new float[3][n];
		for (int i = 0; i <= upto; i++)
			for (int c = 0; c < 3; c++)
				r[c][i] = L[c][i];
		for (int k = 0; k < t.size(); k++) {
			r[0][upto + 1 + k] = (float) t.get(k)[0];
			r[1][upto + 1 + k] = (float) t.get(k)[1];
			r[2][upto + 1 + k] = L[2][upto];
		}
		return r;
	}

	static float[][] tail(float[][] L, int from) {
		int n = Math.max(0, L[0].length - from);
		float[][] r = new float[3][n];
		for (int i = 0; i < n; i++)
			for (int c = 0; c < 3; c++)
				r[c][i] = L[c][from + i];
		return r;
	}

	static double distTo(float[][] L, double[] p) {
		double b = Double.MAX_VALUE;
		for (int k = 0; k < L[0].length; k++)
			b = Math.min(b, Math.hypot(L[0][k] - p[0], L[1][k] - p[1]));
		return b;
	}

	static void updDir(double[] d, double[] p, double[] q) {
		double ux = q[0] - p[0], uy = q[1] - p[1], un = Math.hypot(ux, uy);
		if (un == 0)
			return;
		double x = 0.7 * d[0] + 0.3 * ux / un, y = 0.7 * d[1] + 0.3 * uy / un, n = Math.hypot(x, y);
		d[0] = x / n;
		d[1] = y / n;
	}

	/**
	 * one tracing step: 1 px ahead, then the profile peak across the line nearest
	 * the prediction (within half a line width); null if none
	 */
	double[] step(float[] F, double[] p, double[] d) {
		double qx = p[0] + d[0], qy = p[1] + d[1], nx = -d[1], ny = d[0];
		double best = Double.MAX_VALUE, bs = 0;
		boolean found = false;
		final double h = 0.25;
		for (double s = -w; s <= w; s += h) {
			double g = bilin(F, qx + s * nx, qy + s * ny), gl = bilin(F, qx + (s - h) * nx, qy + (s - h) * ny),
					gr = bilin(F, qx + (s + h) * nx, qy + (s + h) * ny);
			if (g > gl && g >= gr && Math.abs(s) < best) {
				best = Math.abs(s);
				bs = s;
				found = true;
			}
		}
		if (!found || best > w / 2)
			return null;
		return new double[] { qx + bs * nx, qy + bs * ny };
	}

	// ------------------------------------------------------------------ painting

	/**
	 * a stamp of the filament's physical size: xy radius r px, z radius r / (voxel
	 * depth / pixel width) planes, at least the own plane; writes only background
	 * unless force (then also the own label)
	 */
	void paintPhys(int[][] out, double x, double y, double z, double r, int id, boolean force) {
		int xc = (int) Math.round(x), yc = (int) Math.round(y), zc = (int) Math.round(z), ri = (int) Math.ceil(r),
				rzi = (int) Math.floor(r / a);
		for (int zz = zc - rzi; zz <= zc + rzi; zz++) {
			if (zz < 0 || zz >= D)
				continue;
			for (int dy = -ri; dy <= ri; dy++)
				for (int dx = -ri; dx <= ri; dx++) {
					double dzp = (zz - z) * a;
					if (dx * dx + dy * dy + (zz == zc ? 0 : dzp * dzp) > r * r)
						continue;
					int xx = xc + dx, yy = yc + dy;
					if (xx < 0 || yy < 0 || xx >= W || yy >= H)
						continue;
					if (out[zz][yy * W + xx] == 0 || (force && out[zz][yy * W + xx] == id))
						out[zz][yy * W + xx] = id;
				}
		}
	}

	/**
	 * join the 26-connected pieces of each label: a 3D segment between the nearest
	 * kept points of different pieces, until one piece
	 */
	void bridge(int[][] out, List<float[]> pts, double r) {
		Map<Integer, List<float[]>> byLab = new HashMap<>();
		for (float[] p : pts) {
			int L = out[Math.round(p[2])][clampY(Math.round(p[1])) * W + clampX(Math.round(p[0]))];
			if (L > 0)
				byLab.computeIfAbsent(L, k -> new ArrayList<>()).add(p);
		}
		for (Map.Entry<Integer, List<float[]>> e : new TreeMap<>(byLab).entrySet()) {
			int L = e.getKey();
			List<float[]> ps = e.getValue();
			for (int guard = 0; guard < 50; guard++) {
				int[] comp = pieceOf(out, L, ps);
				int c0 = comp[0];
				double best = Double.MAX_VALUE;
				int bi = -1, bj = -1;
				for (int i = 0; i < ps.size(); i++)
					if (comp[i] == c0)
						for (int j = 0; j < ps.size(); j++)
							if (comp[j] != c0 && comp[j] >= 0) {
								float[] p = ps.get(i), q = ps.get(j);
								double d = Math.pow(p[0] - q[0], 2) + Math.pow(p[1] - q[1], 2)
										+ Math.pow((p[2] - q[2]) * a, 2);
								if (d < best) {
									best = d;
									bi = i;
									bj = j;
								}
							}
				if (bi < 0)
					break;
				float[] p = ps.get(bi), q = ps.get(bj);
				int ns = (int) Math.ceil(2 * Math.sqrt(best)) + 1;
				for (int k = 0; k <= ns; k++) {
					double t = k / (double) ns;
					paintPhys(out, p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1]), p[2] + t * (q[2] - p[2]), r, L,
							true);
				}
			}
		}
	}

	/**
	 * piece index of each point's voxel (flood fill, 26-connected, over voxels of
	 * label L)
	 */
	int[] pieceOf(int[][] out, int L, List<float[]> ps) {
		Map<Long, Integer> vox = new HashMap<>();
		int[] comp = new int[ps.size()];
		Arrays.fill(comp, -1);
		int nc = 0;
		for (int s = 0; s < ps.size(); s++) {
			float[] p = ps.get(s);
			int z0 = Math.round(p[2]), x0 = clampX(Math.round(p[0])), y0 = clampY(Math.round(p[1]));
			long k0 = ((long) z0 * H + y0) * W + x0;
			if (vox.containsKey(k0)) {
				comp[s] = vox.get(k0);
				continue;
			}
			if (out[z0][y0 * W + x0] != L)
				continue;
			ArrayDeque<int[]> qq = new ArrayDeque<>();
			vox.put(k0, nc);
			qq.add(new int[] { z0, x0, y0 });
			while (!qq.isEmpty()) {
				int[] c = qq.poll();
				for (int dz = -1; dz <= 1; dz++)
					for (int dy = -1; dy <= 1; dy++)
						for (int dx = -1; dx <= 1; dx++) {
							int zz = c[0] + dz, xx = c[1] + dx, yy = c[2] + dy;
							if (zz < 0 || zz >= D || xx < 0 || yy < 0 || xx >= W || yy >= H
									|| out[zz][yy * W + xx] != L)
								continue;
							long kk = ((long) zz * H + yy) * W + xx;
							if (vox.containsKey(kk))
								continue;
							vox.put(kk, nc);
							qq.add(new int[] { zz, xx, yy });
						}
			}
			comp[s] = nc++;
		}
		return comp;
	}

	// ------------------------------------------------------------------ joining
	// with kept-apart pairs

	int find(int i) {
		return findIn(par, i);
	}

	static int findIn(int[] p, int i) {
		while (p[i] != i) {
			p[i] = p[p[i]];
			i = p[i];
		}
		return i;
	}

	static void unionIn(int[] p, int a, int b) {
		a = findIn(p, a);
		b = findIn(p, b);
		if (a != b)
			p[Math.max(a, b)] = Math.min(a, b);
	}

	/**
	 * a join that honours the kept-apart pairs (hard always; soft unless honourSoft
	 * is false)
	 */
	boolean cunion(int i, int j, boolean honourSoft) {
		int x = find(i), y = find(j);
		if (x == y)
			return true;
		if (forb != null) {
			Set<Integer> f = forb.get(x);
			if (f != null && f.contains(y))
				return false;
		}
		if (honourSoft && forbPar != null) {
			Set<Integer> f = forbPar.get(x);
			if (f != null && f.contains(y))
				return false;
		}
		int r = Math.min(x, y), o = Math.max(x, y);
		par[o] = r;
		mergeForb(forb, r, o);
		mergeForb(forbPar, r, o);
		return true;
	}

	void union(int i, int j) {
		if (forb == null && forbPar == null)
			unionIn(par, i, j);
		else
			cunion(i, j, true);
	}

	/**
	 * the crossing pairing at a junction: honours the hard pairs; the soft ones
	 * (parallel, the 3D branch and crossing pairs) do not bind it
	 */
	void unionAtJunction(int i, int j) {
		if (forb == null && forbPar == null)
			unionIn(par, i, j);
		else
			cunion(i, j, false);
	}

	static void mergeForb(Map<Integer, Set<Integer>> m, int r, int o) {
		if (m == null)
			return;
		Set<Integer> fo = m.remove(o);
		if (fo != null) {
			Set<Integer> fr = m.computeIfAbsent(r, k -> new HashSet<>());
			for (int v : fo) {
				if (v == r)
					continue;
				fr.add(v);
				Set<Integer> fv = m.get(v);
				if (fv != null) {
					fv.remove(o);
					fv.add(r);
				}
			}
		}
		Set<Integer> fr = m.get(r);
		if (fr != null) {
			fr.remove(r);
			fr.remove(o);
		}
	}

	// ------------------------------------------------------------------ helpers

	int nearest(Map<Long, List<Integer>> grid, float[] p, int z2) {
		if (z2 < 0 || z2 >= D)
			return -1;
		int best = -1;
		double bd = tol;
		for (int gx = -1; gx <= 1; gx++)
			for (int gy = -1; gy <= 1; gy++)
				for (int j : grid.getOrDefault(key(z2, (int) (p[0] / 4) + gx, (int) (p[1] / 4) + gy),
						Collections.<Integer>emptyList())) {
					float[] q = P.get(j);
					double d = Math.hypot(q[0] - p[0], q[1] - p[1]);
					if (d <= bd && Math.abs(q[4] * p[4] + q[5] * p[5]) >= COS30) {
						bd = d;
						best = j;
					}
				}
		return best;
	}

	double bilin(float[] s, double x, double y) {
		int x0 = Math.max(0, Math.min(W - 2, (int) Math.floor(x))),
				y0 = Math.max(0, Math.min(H - 2, (int) Math.floor(y)));
		double fx = Math.max(0, Math.min(1, x - x0)), fy = Math.max(0, Math.min(1, y - y0));
		return (1 - fy) * ((1 - fx) * s[y0 * W + x0] + fx * s[y0 * W + x0 + 1])
				+ fy * ((1 - fx) * s[(y0 + 1) * W + x0] + fx * s[(y0 + 1) * W + x0 + 1]);
	}

	boolean near3(float[] p, float[] q, double zp, double zq, double d) {
		double dx = p[0] - q[0], dy = p[1] - q[1], dz = (zp - zq) * a;
		return dx * dx + dy * dy + dz * dz <= d * d;
	}

	double linkCost(float[] p, float[] q) {
		return Math.hypot(q[0] - p[0], q[1] - p[1]) + w * (1 - Math.abs(q[4] * p[4] + q[5] * p[5]));
	}

	int clampX(int x) {
		return Math.max(0, Math.min(W - 1, x));
	}

	int clampY(int y) {
		return Math.max(0, Math.min(H - 1, y));
	}

	static long pkey(float[] p) {
		return ((long) p[6] << 24) | (long) p[7];
	} // (line, index in line); index < 2^24

	static long key(int z, int gx, int gy) {
		return ((long) z << 40) | ((long) (gx & 0xfffff) << 20) | (gy & 0xfffff);
	}

	static long fkey(float x, float y) {
		return ((long) Float.floatToIntBits(x) << 32) | (Float.floatToIntBits(y) & 0xffffffffL);
	}

	static double dot(double[] u, double[] v) {
		return u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
	}

	static double norm(double[] u) {
		return Math.sqrt(dot(u, u));
	}
}
