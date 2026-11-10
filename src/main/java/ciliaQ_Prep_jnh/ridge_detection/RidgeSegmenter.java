package ciliaQ_Prep_jnh.ridge_detection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ciliaQ_Prep_jnh.ProgressReporter;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.Line;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.LineDetector;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.Lines;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.OverlapOption;

import ij.ImagePlus;
import ij.ImageStack;
import ij.CompositeImage;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;
import ij.process.ShortProcessor;

/**
 * CiliaQ Preparator - RIDGE DETECTION segmentation (Steger line detector), producing a LABEL image.
 *
 * Detects filamentous structures and writes one integer label per object into the output channel, so
 * that touching filaments can later be told apart (0 = background, 1..N = objects). All of the detection,
 * z-linking and label bookkeeping lives here; CiliaQPrepMain only calls detect(...) and then writes the
 * result, keeping the change to the main plugin small.
 *
 * HOW IT WORKS.
 *  1. DETECTION IS 2D, PER PLANE. The Steger detector operates on a single image, so each z-slice is
 *     detected independently. The Gaussian sigma and the upper/lower thresholds are derived ONCE from
 *     stack-wide statistics (in RidgeSettings) and reused for every plane - deriving them per slice
 *     makes the line count swing wildly from plane to plane, which is threshold inconsistency rather
 *     than real structure and makes z-linking impossible.
 *  2. THE Z METHOD (RidgeSettings.zMethod, new in v0.2.1). Classic 2D (below, and every single-plane image)
 *     paints every plane a line is found in and joins lines of adjacent planes that share pixels (union-find,
 *     largest overlap first; the overlap is NOT dilated, dilation raises the over-fusion). "2D in-focus
 *     plane" and "3D" keep each detection only in its in-focus plane and join it to its 3D neighbours; 3D
 *     also pairs the branches of crossings across planes (RidgeZ).
 *  3. CLEAN-UP RULES (RidgeCleanup, new in v0.2.1). Per plane, lines that belong to different filaments -
 *     a line ending on another's side, two lines traced through one crossing, optionally lines running
 *     side by side or the arms of a kink - are kept apart: they are never joined into one object.
 *     The minimum length applies to the finished object (its distinct xy positions in classic), so the
 *     short in-focus pieces of a tilted filament are not lost; Steger keeps pieces down to 2 px per plane.
 *  4. CONSECUTIVE LABELS. Final labels are consecutive positive integers from 1, numbered from the
 *     voxels actually painted (not from line indices - a line fully covered by an earlier line keeps an
 *     id but no pixels). Labels wrap at 2^24, above which float32 can no longer hold consecutive
 *     integers exactly; a wrap is reported rather than silently producing duplicate ids.
 *
 * SAFETY CAP. A plane that yields far more lines than pixels is not detecting filaments but tracing
 * noise, and can exhaust the heap. LINE_CAP_DIVISOR bounds the number of lines per plane and aborts the
 * channel with an explanatory message, anchored on that failure mode rather than on a noise statistic
 * (which is zero on background-free images and so would never fire when needed).
 *
 * OUTPUT BIT DEPTH. Detection writes into a scratch array first; only once the object count is known
 * does the caller promote the output image to the smallest bit depth that holds the labels exactly (see
 * requiredBitDepth and promoteKeepingValues). A Glasbey LUT is applied for legibility (applyGlasbeyLut).
 *
 * STEGER LINE DETECTOR
 * This technique bases on
 *  
 * > Steger, C., 1998. An unbiased detector of curvilinear structures.
 * IEEE Transactions on Pattern Analysis and Machine Intelligence, 20(2), pp.113–125.
 * 
 * and uses a corrected copy of ImageJ's implementation (https://imagej.net/plugins/ridge-detection),
 * vendored in ciliaQ_Prep_jnh.ij_ridge_detector_steger - its README lists what was changed and why.
 *
 * Added in v0.2.1.
 *
 * @author Jan N. Hansen
 */
public class RidgeSegmenter {

	/** float32 represents consecutive integers exactly only up to 2^24. */
	public static final int MAX_LABEL = 16777216;

	/**
	 * Hard safety cap on detected lines per plane, as a fraction of the pixel count.
	 *
	 * The MAD-based guard in RidgeSettings.rejectionReason() is not enough: MAD is 0 on background-free
	 * data, so that noise-anchored guard never fires exactly where it is needed, and the detector can
	 * produce enough Line objects to exhaust the heap.
	 *
	 * This cap is anchored on the failure mode itself rather than on a noise proxy: a plane that yields
	 * more lines than one per LINE_CAP_DIVISOR pixels is not detecting filaments.
	 */
	public static final int LINE_CAP_DIVISOR = 40;

	/** Result of a detection pass: per-plane label arrays plus how many distinct objects were found. */
	public static class Result {
		public final int[][] labels;	// [slice][y*width + x], 0 = background
		public final int nObjects;
		public final int nLinesTotal;
		public final int width, height, nSlices;
		public final boolean wrapped;
		Result(int[][] labels, int nObjects, int nLinesTotal, int w, int h, int d, boolean wrapped) {
			this.labels = labels; this.nObjects = nObjects; this.nLinesTotal = nLinesTotal;
			this.width = w; this.height = h; this.nSlices = d; this.wrapped = wrapped;
		}
	}

	// ------------------------------------------------------------------ detection

	/**
	 * Detect filaments in every plane of a single-channel, single-timepoint image and link them in z.
	 * Writes nothing: the caller decides the output bit depth once nObjects is known, which is why this
	 * returns a scratch array (the pattern the rest of the Preparator uses).
	 */
	public static Result detect(ImagePlus channelImp, RidgeSettings rs,  ProgressReporter progress) throws IllegalStateException {
		final int W = channelImp.getWidth(), H = channelImp.getHeight(), D = channelImp.getStackSize();

		// One warning per call (one timepoint) is enough; avoids spam if several planes are slow.
		final boolean[] warned = { false };
		// 45 s for a single plane is far beyond normal; a correctly-thresholded 2048x2048 plane is seconds.
		final SlowWatchdog watchdog = new SlowWatchdog(45_000, () -> {
			if(warned[0]) return;
			warned[0] = true;
			progress.notifyMessage("Ridge detection is taking unusually long on this image. This usually"
					+ " means the thresholds are too permissive and the detector is tracing noise rather"
					+ " than filaments. You can let it finish, but if it does not complete soon, cancel and"
					+ " raise 'contrast sensitivity'. (This message appears once and does not stop"
					+ " processing.)", ProgressReporter.NOTIFICATION);
		});

		/*	New V0.2.1: the z method. On a single plane all three methods are the same, so a single plane always takes the
		 *	classic path (with the clean-up rules). */
		int[][] perVoxel; int nLines;
		if (D > 1 && !RidgeSettings.Z_CLASSIC.equals(rs.zMethod)) {
			RidgeZ.Output o = RidgeZ.detect(channelImp, rs, RidgeSettings.Z_3D.equals(rs.zMethod), progress, watchdog);
			perVoxel = o.labels; nLines = o.nLines;
		} else {
			int[] nl = new int[1];
			perVoxel = classic(channelImp, rs, progress, watchdog, nl);
			nLines = nl[0];
		}
		return numbered(perVoxel, nLines, W, H, D);
	}

	/**
	 * Classic 2D (all planes): Steger per plane, every line painted in its plane, lines of neighbouring planes joined where
	 * they share pixels. New V0.2.1:
	 *  - the clean-up rules run per plane (RidgeCleanup); the pairs they keep apart are never joined, and the joins through z
	 *    are made best first (largest overlap first), since the order matters once some joins are refused;
	 *  - last, short pieces next to a filament are joined to it or dropped (step 6, RidgeShortPieces);
	 *  - the minimum length applies to the finished object, measured on its projection (distinct line positions in xy, so a
	 *    filament seen in several planes counts once); per plane Steger keeps pieces down to 2 px, so the short in-focus
	 *    pieces of a tilted filament are not lost.
	 * @return labels per plane [z][y*W + x] numbered by object (not yet consecutive); nLinesOut[0] = lines detected
	 */
	static int[][] classic(ImagePlus channelImp, RidgeSettings rs, ProgressReporter progress, SlowWatchdog watchdog, int[] nLinesOut) {
		final int W = channelImp.getWidth(), H = channelImp.getHeight(), D = channelImp.getStackSize();
		final OverlapOption overlap = rs.resolveCrossings ? OverlapOption.SLOPE : OverlapOption.NONE;
		final RidgeCleanup rules = rs.cleanup();
		final boolean anyRule = rules.any();
		int[][] perPlane = new int[D][]; // per slice: the label array [y*W + x], local line ids from 1
		int[] perPlaneCount = new int[D]; // per slice: how many lines were rasterised in that plane
		int[] offset = new int[D + 1]; // running start index per slice into the global line numbering
		List<List<float[][]>> linesZ = new ArrayList<List<float[][]>>();
		List<List<int[]>> apartZ = new ArrayList<List<int[]>>();

		for (int z = 0; z < D; z++) {
			progress.updateBarText("Ridge detection slice " + (z + 1) + "/" + D);
			ImageProcessor ip = channelImp.getStack().getProcessor(z + 1);
			int[] lab = new int[W * H];
			List<float[][]> LS = new ArrayList<float[][]>();
			List<int[]> apart = new ArrayList<int[]>();
			try {
				Lines lines = detectPlane(ip, rs, MIN_PIECE, overlap, watchdog);
				checkCap(lines.size(), W, H, z);
				for (Line l : lines)
					LS.add(rs.estimateWidth
							? new float[][] { l.getXCoordinates(), l.getYCoordinates(), l.getResponse(),
									l.getLineWidthL(), l.getLineWidthR() }
							: new float[][] { l.getXCoordinates(), l.getYCoordinates(), l.getResponse() });
				nLinesOut[0] += lines.size();
				if (anyRule) {
					rules.setPlane(ip);
					LS = rules.apply(LS, rs.lineWidth, apart);
				}
			} catch (IllegalStateException overCap) {
				/*
				 * The safety cap is fatal for the whole channel, not a per-plane hiccup:
				 * continuing would hit the same wall on the next plane, or exhaust the heap
				 * before it got there.
				 */
				throw overCap;
			} catch (Throwable e) {
				StringBuilder trace = new StringBuilder();
				for (StackTraceElement line : e.getStackTrace()) {
					trace.append(" \n ").append(line.toString());
				}
				// Any other failure on one plane must not lose the others.
				progress.notifyMessage(
						"CiliaQ Preparator: ridge detection failed on plane " + (z + 1) + " - " + e + trace,
						ProgressReporter.NOTIFICATION);
				LS = new ArrayList<float[][]>();
				apart = new ArrayList<int[]>();
			}
			int n = 0;
			for (float[][] L : LS)
				rasterise(L, lab, W, H, ++n);
			perPlane[z] = lab;
			perPlaneCount[z] = n;
			linesZ.add(LS);
			apartZ.add(apart);
		}
		offset[0] = 0;
		for (int z = 0; z < D; z++)
			offset[z + 1] = offset[z] + perPlaneCount[z];
		final int totalLines = offset[D];

		// union-find over GLOBAL line ids offset[z] + localId (1..totalLines; 0 is
		// background)
		int[] parent = new int[totalLines + 1];
		for (int i = 0; i <= totalLines; i++)
			parent[i] = i;
		/*
		 * the rules' pairs, by global line id: hard (kink, branch, crossing) bind every
		 * join, soft (parallel) too here - classic has no crossing pairing for them to
		 * give way to. dropHairpins first releases parallel pairs that a nearby plane
		 * traces as one fold.
		 */
		Map<Integer, Set<Integer>> hard = null, soft = null;
		if (anyRule) {
			rules.dropHairpins(linesZ, apartZ, rs.lineWidth);
			hard = new HashMap<Integer, Set<Integer>>();
			soft = new HashMap<Integer, Set<Integer>>();
			for (int z = 0; z < D; z++)
				for (int[] q : apartZ.get(z)) {
					int x = offset[z] + q[0] + 1, y = offset[z] + q[1] + 1;
					Map<Integer, Set<Integer>> m = q[2] == RidgeCleanup.PARALLEL ? soft : hard;
					add(m, x, y);
					add(m, y, x);
				}
		}
		/*
		 * z-link by pixel overlap, largest overlap first (pairs of lines in
		 * neighbouring planes, counted over shared pixels; ties by id)
		 */
		HashMap<Long, Integer> overlapCount = new HashMap<Long, Integer>();
		for (int z = 0; z + 1 < D; z++)
			for (int i = 0; i < W * H; i++) {
				int a = perPlane[z][i], b = perPlane[z + 1][i];
				if (a == 0 || b == 0)
					continue;
				overlapCount.merge(((long) (offset[z] + a) << 32) | ((offset[z + 1] + b) & 0xffffffffL), 1,
						Integer::sum);
			}
		List<Map.Entry<Long, Integer>> order = new ArrayList<Map.Entry<Long, Integer>>(overlapCount.entrySet());
		order.sort((u, v) -> v.getValue().intValue() != u.getValue().intValue() ? v.getValue() - u.getValue()
				: Long.compare(u.getKey(), v.getKey()));
		for (Map.Entry<Long, Integer> e : order)
			join(parent, hard, soft, (int) (e.getKey() >>> 32), (int) (e.getKey() & 0xffffffffL));

		// step 6, last: short pieces next to a filament, joined or dropped
		Set<Integer> dropped = shortPieces(parent, hard, soft, linesZ, offset, D, rs);

		// minimum length on the object: distinct line positions in xy
		Map<Integer, Set<Long>> xy = xyByObject(parent, linesZ, offset, D);
		int[][] out = new int[D][W * H];
		for (int z = 0; z < D; z++)
			for (int i = 0; i < W * H; i++) {
				int l = perPlane[z][i];
				if (l == 0)
					continue;
				int r = find(parent, offset[z] + l);
				if (xy.get(r).size() < rs.minLength || dropped.contains(r))
					continue;
				out[z][i] = r;
			}
		return out;
	}

	/** per object (root): its distinct line positions in xy (the minimum length counts them) */
	static Map<Integer, Set<Long>> xyByObject(int[] parent, List<List<float[][]>> linesZ, int[] offset, int D) {
		Map<Integer, Set<Long>> xy = new HashMap<Integer, Set<Long>>();
		for (int z = 0; z < D; z++) {
			List<float[][]> LS = linesZ.get(z);
			for (int l = 0; l < LS.size(); l++) {
				Set<Long> s = xy.computeIfAbsent(find(parent, offset[z] + l + 1), k -> new HashSet<Long>());
				float[][] L = LS.get(l);
				for (int i = 0; i < L[0].length; i++)
					s.add(((long) Math.round(L[0][i]) << 32) | (Math.round(L[1][i]) & 0xffffffffL));
			}
		}
		return xy;
	}

	/**
	 * Step 6 on the objects of classic (RidgeShortPieces), in xy only: classic paints every plane a line is seen in, so
	 * the planes say nothing about how far an object extends in z. Joins override the pairs kept apart. Returns the dropped
	 * objects.
	 */
	static Set<Integer> shortPieces(int[] p, Map<Integer, Set<Integer>> hard, Map<Integer, Set<Integer>> soft, List<List<float[][]>> linesZ,
			int[] offset, int D, RidgeSettings rs) {
		Set<Integer> dropped = new HashSet<Integer>();
		final int mode = rs.shortPiecesMode();
		if (mode == RidgeShortPieces.APART)
			return dropped;
		java.util.TreeMap<Integer, List<float[]>> obj = new java.util.TreeMap<Integer, List<float[]>>();
		for (int z = 0; z < D; z++) {
			List<float[][]> LS = linesZ.get(z);
			for (int l = 0; l < LS.size(); l++) {
				float[][] L = LS.get(l);
				List<float[]> ps = obj.computeIfAbsent(find(p, offset[z] + l + 1), k -> new ArrayList<float[]>());
				for (int i = 0; i < L[0].length; i++)
					ps.add(new float[] { L[0][i], L[1][i], 0f });
			}
		}
		Set<Integer> kept = new HashSet<Integer>(); // the objects the minimum length keeps: only these take pieces
		for (Map.Entry<Integer, Set<Long>> e : xyByObject(p, linesZ, offset, D).entrySet())
			if (e.getValue().size() >= rs.minLength)
				kept.add(e.getKey());
		for (Map.Entry<Integer, Integer> e : RidgeShortPieces.find(obj, kept, rs.lineWidth, 0).entrySet()) {
			if (mode == RidgeShortPieces.DROP) {
				dropped.add(e.getKey());
				continue;
			}
			int r = find(p, e.getKey()), t = find(p, e.getValue());
			if (r == t)
				continue;
			int n = Math.min(r, t), o = Math.max(r, t);
			p[o] = n;
			RidgeZ.mergeForb(hard, n, o);
			RidgeZ.mergeForb(soft, n, o);
		}
		return dropped;
	}

	/**
	 * The z notes for one channel, or null: in-focus / 3D on a stack without voxel size, or Classic with short pieces
	 * joined or dropped (Classic paints every plane a line is seen in, so it cannot tell a standing cilium from a short
	 * piece). Called ONCE per channel by the caller.
	 */
	public static String stackNote(ImagePlus imp, RidgeSettings rs) {
		if (imp.getNSlices() <= 1) return null;
		if (!RidgeSettings.Z_CLASSIC.equals(rs.zMethod)) {
			if (imp.getCalibration().scaled()) return null;
			return "ridge detection (" + rs.zMethodToken() + "): this stack has no voxel size, so voxels are treated as"
					+ " cubes and filaments stacked in z join too easily. Set the voxel size (Image > Properties) and run again.";
		}
		if (RidgeSettings.SHORT_APART.equals(rs.shortPieces)) return null;
		return "ridge detection (classic): short pieces are set to '" + rs.shortPieces + "'. Classic has no z"
				+ " information, so an upright (standing) cilium - short in x,y but tall in z - can be mistaken for a short piece and "
				+ (RidgeSettings.SHORT_DROP.equals(rs.shortPieces) ? "dropped" : "merged into a neighbour") + ". With standing cilia,"
				+ " use the 'in-focus' or '3D' z method, or set short pieces to 'keep apart' (CiliaQ's minimum size filter can then"
				+ " remove the short pieces).";
	}
	
	/** Steger on one plane, timed by the watchdog */
	static Lines detectPlane(ImageProcessor ip, RidgeSettings rs, double minLength, OverlapOption overlap, SlowWatchdog watchdog) {
		LineDetector det = new LineDetector().setLineWidth(rs.lineWidth);	// New V0.2.1: the resolver gets the user's width
		watchdog.arm();						// start the clock for THIS plane
		try {
			return det.detectLines(ip, rs.sigma, rs.upperThreshold, rs.lowerThreshold, minLength, rs.maxLength, rs.darkLine,
					rs.correctPosition, rs.estimateWidth, rs.extendLine, overlap);
		} finally {
			watchdog.disarm();				// plane finished (or threw) - stop the clock
		}
	}

	/** Steger's minimum length per plane, in px: the user's minimum applies to the finished object (New V0.2.1). */
	static final double MIN_PIECE = 2;

	/** the safety cap on lines per plane (see LINE_CAP_DIVISOR) */
	static void checkCap(int nLines, int W, int H, int z) {
		int cap = Math.max(2000, (W * H) / LINE_CAP_DIVISOR);
		if (nLines > cap) {
			throw new IllegalStateException("ridge detection produced " + nLines
					+ " lines on plane " + (z + 1) + ", above the safety cap of " + cap
					+ " (one per " + LINE_CAP_DIVISOR + " pixels). The thresholds are too"
					+ " permissive for this image - raise 'contrast sensitivity'.");
		}
	}

	/**
	 * Final labels: consecutive positive integers from 1, in order of the first painted voxel (scan order: plane, then
	 * y*W + x), so labels never skip a value and nObjects is the number of objects present. Labels wrap at 2^24, above which
	 * float32 can no longer hold consecutive integers exactly; a wrap is reported rather than silently producing duplicates.
	 */
	static Result numbered(int[][] perVoxel, int nLines, int W, int H, int D) {
		HashMap<Integer, Integer> idOf = new HashMap<Integer, Integer>();
		boolean wrapped = false;
		int[][] out = new int[D][W * H];
		for (int z = 0; z < D; z++) for (int i = 0; i < W * H; i++) {
			int v = perVoxel[z][i]; if (v == 0) continue;
			Integer id = idOf.get(v);
			if (id == null) {
				int next = idOf.size() + 1;
				if (next > MAX_LABEL) { wrapped = true; next = ((idOf.size()) % MAX_LABEL) + 1; }
				id = next; idOf.put(v, id);
			}
			out[z][i] = id;
		}
		return new Result(out, idOf.size(), nLines, W, H, D, wrapped);
	}

	/**
	 * a join that honours the kept-apart pairs; the forbidden sets follow the
	 * merged root
	 */
	private static void join(int[] p, Map<Integer, Set<Integer>> hard, Map<Integer, Set<Integer>> soft, int a, int b) {
		int x = find(p, a), y = find(p, b);
		if (x == y)
			return;
		if (hard != null) {
			Set<Integer> f = hard.get(x);
			if (f != null && f.contains(y))
				return;
		}
		if (soft != null) {
			Set<Integer> f = soft.get(x);
			if (f != null && f.contains(y))
				return;
		}
		int r = Math.min(x, y), o = Math.max(x, y);
		p[o] = r;
		RidgeZ.mergeForb(hard, r, o);
		RidgeZ.mergeForb(soft, r, o);
	}
	
	private static void add(Map<Integer, Set<Integer>> m, int x, int y) {
		m.computeIfAbsent(x, k -> new HashSet<Integer>()).add(y);
	}

	/**
	 * Paint one detected line into the per-plane label array.
	 *
	 * The Steger detector returns a line as a sequence of sub-pixel points along its centre. This stamps a
	 * filled DISC at each point, so the whole line is drawn with thickness: the disc radius is half the
	 * line's locally estimated width (when estimateWidth is on), giving a thin line where the structure is
	 * thin and a wider one where it is wide. If width estimation is off, or unavailable at a point, a
	 * minimal radius is used so the line is still at least one pixel thick.
	 *
	 * FIRST WRITER WINS: a voxel is claimed only if it is still background (0). Earlier lines therefore keep
	 * their pixels where lines overlap, rather than being overwritten. One consequence is that a line whose
	 * footprint is entirely covered by earlier lines paints nothing - which is why object numbering later
	 * counts PAINTED voxels, not line indices (see numbered()).
	 *
	 * @param L             the line as rows {x, y, response} or, with estimated widths, {x, y, response, width left, width right}
	 *                      (New V0.2.1: rows instead of the Line object, so a line the kink rule cut keeps its widths)
	 * @param lab           the plane's label array [y*W + x] to paint into; 0 = background
	 * @param W, H          plane dimensions, for bounds checking and indexing
	 * @param id            the local (per-plane) label to write for this line
	 */
	private static void rasterise(float[][] L, int[] lab, int W, int H, int id) {
		float[] xs = L[0], ys = L[1];
		float[] wl = L.length > 4 ? L[3] : null;
		float[] wr = L.length > 4 ? L[4] : null;
		for (int i = 0; i < xs.length; i++) {
			double halfWidth = (wl != null && wr != null && i < wl.length && i < wr.length)
					? Math.max(0.5, (wl[i] + wr[i]) / 2.0) : 1.0;
			int r = (int) Math.ceil(halfWidth);
			for (int dy = -r; dy <= r; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					if (dx * dx + dy * dy > halfWidth * halfWidth) continue;
					int x = (int) Math.round(xs[i]) + dx, y = (int) Math.round(ys[i]) + dy;
					if (x < 0 || x >= W || y < 0 || y >= H) continue;
					if (lab[y * W + x] == 0) lab[y * W + x] = id;
				}
			}
		}
	}

	/**
	 * Union-find "find": return the ROOT of the group that element a belongs to, by following parent
	 * pointers up until one points to itself. Flattens the path on the way (p[a] = p[p[a]]) so later
	 * lookups on the same branch are faster.
	 */
	private static int find(int[] p, int a) {
		while (p[a] != a) {
			p[a] = p[p[a]];
			a = p[a];
		}
		return a;
	}

	// ------------------------------------------------------------------ bit depth

	/** Smallest bit depth that can hold labels 0..nObjects as exact integers. */
	public static int requiredBitDepth(int nObjects) {
		if (nObjects <= 255) return 8;
		if (nObjects <= 65535) return 16;
		return 32;
	}

	/**
	 * Promote an image to a deeper bit depth KEEPING EVERY VALUE IDENTICAL.
	 *
	 * Deliberately does NOT use ij.process.ImageConverter: its behaviour depends on the static global
	 * ImageConverter.setDoScaling(boolean), which any other plugin in the session may have flipped, and
	 * scaling would destroy label values and alter intensity channels. Copying voxel by voxel is exact
	 * for 8 -> 16 and for 8/16 -> 32 (integers up to 2^24 are exact in float32).
	 *
	 * @return a new ImagePlus with the same dimensions, calibration and values; or the input unchanged
	 *         if it is already deep enough.
	 */
	public static ImagePlus promoteKeepingValues(ImagePlus imp, int targetBitDepth) {
		if (imp.getBitDepth() >= targetBitDepth) {
			// already deep enough - nothing to do
			return imp;
		}
		
		final int W = imp.getWidth(), H = imp.getHeight();
		final int nC = imp.getNChannels(), nZ = imp.getNSlices(), nT = imp.getNFrames();
		ImageStack src = imp.getStack();
		ImageStack dst = new ImageStack(W, H);	// the promoted stack we build up
		for (int i = 1; i <= src.getSize(); i++) {
			// copy every slice of the (flat) stack
			ImageProcessor sp = src.getProcessor(i);
			
			// a deeper processor of the target type; 16-bit -> ShortProcessor, 32-bit -> FloatProcessor
			ImageProcessor dp = (targetBitDepth == 16) ? new ShortProcessor(W, H)
					: new FloatProcessor(W, H);
			for (int k = 0; k < W * H; k++) dp.setf(k, sp.getf(k));
			dst.addSlice(src.getSliceLabel(i), dp);	// keep the original slice label
		}
		ImagePlus out = new ImagePlus(imp.getTitle(), dst);
		out.setCalibration(imp.getCalibration());
		out.setDimensions(nC, nZ, nT);
		if (nC > 1) out.setOpenAsHyperStack(true);	// a multi-channel stack must be a hyperstack

		/*
		 * Carry over composite mode and the per-channel LUTs. Without this the promoted image comes back
		 * with composite = false and every channel LUT lost, so a Glasbey LUT applied to an earlier
		 * channel is wiped as soon as a later channel triggers promotion. (Pixel values are preserved
		 * regardless; it is only the display state that would otherwise be thrown away.)
		 */
		if (imp.isComposite() && nC > 1) {
			CompositeImage src2 = (CompositeImage) imp;
			CompositeImage co = new CompositeImage(out, src2.getMode());	// same composite display mode
			for (int c = 1; c <= nC; c++) {										// copy each channel's lookup table across
				LUT l = src2.getChannelLut(c);
				if (l != null) co.setChannelLut(l, c);
			}
			co.setCalibration(imp.getCalibration());
			return co;
		}
		return out;
	}

	// ------------------------------------------------------------------ LUT

	/**
	 * Apply a Glasbey LUT to one channel so a label image is readable at a glance. A Glasbey LUT gives
	 * neighbouring label values very different colours, so adjacent objects are easy to tell apart.
	 * glasbey_on_dark keeps index 0 black, which is what background should look like.
	 * Silently does nothing if the LUT file is not present, since this is cosmetic.
	 */
	public static void applyGlasbeyLut(ImagePlus imp, int channel) {
		try {
			// preferred first; fall back to other Glasbey variants if the dark one is not installed.
			// LutLoader.getLut finds a LUT by name in Fiji's luts folder, reads the binary and the text
			// formats (Fiji's Glasbey LUTs are text; glasbey.lut has a header row), and returns null without
			// a message if the LUT is not installed.
			String[] candidates = { "glasbey_on_dark", "glasbey_inverted", "glasbey" };
			java.awt.image.IndexColorModel cm = null;
			for (int i = 0; i < candidates.length && cm == null; i++) {
				cm = ij.plugin.LutLoader.getLut(candidates[i]);	// stop at the first that exists
			}
			if (cm == null) return;	// no Glasbey LUT installed - leave the display as is

			byte[] r = new byte[256], g = new byte[256], b = new byte[256];
			cm.getReds(r); cm.getGreens(g); cm.getBlues(b);
			r[0] = 0; g[0] = 0; b[0] = 0;	// index 0 is background (labels start at 1): keep it black, as before
			LUT lut = new LUT(r, g, b);
			
			if (imp.isComposite()) {
				// multi-channel: set the LUT on this channel alone, leaving the others untouched
				((CompositeImage) imp).setChannelLut(lut, channel);
			} else {
				// single-channel: the LUT applies to the whole image
				imp.getProcessor().setColorModel(lut);
			}
		} catch (Throwable e) {
			// Never let a missing or malformed LUT stop a segmentation run
		}
	}

	// ------------------------------------------------------------------ writing

	/**
	 * Write one timepoint's labels into the output image.
	 *
	 * Copies the per-slice label arrays from a detection Result into the given channel/frame of the
	 * output hyperstack. The output image must already be deep enough to hold the label values exactly -
	 * promote it with promoteKeepingValues before calling this, or large labels will be truncated.
	 *
	 * @param res             the detection result, holding labels[slice][y*width + x]
	 * @param writeImp        the output image to write into (already at a sufficient bit depth)
	 * @param channelWriteImp the 1-based channel in writeImp to write the labels to
	 * @param frame           the 0-based timepoint; converted to 1-based for ImageJ's stack index
	 */
	public static void writeLabels(Result res, ImagePlus writeImp, int channelWriteImp, int frame) {
		for (int s = 0; s < res.nSlices; s++) {
			/* ImageJ stack indices are 1-based for channel/slice/frame; getStackIndex returns 1-based,
			 * so subtract 1 to get the 0-based index that setVoxel expects for the z/stack position */
			int idx = writeImp.getStackIndex(channelWriteImp, s + 1, frame + 1) - 1;
			for (int x = 0; x < res.width; x++) {
				for (int y = 0; y < res.height; y++) {
					// res.labels is laid out as y*width + x; write it to voxel (x, y, idx)
					writeImp.getStack().setVoxel(x, y, idx, res.labels[s][y * res.width + x]);
				}
			}
		}
	}

	/**
	 * Runs {@code onSlow} once if {@code armMillis} elapse before disarm() is called. Used to warn, without
	 * blocking or interrupting, when a single detectLines() call is taking unusually long - the symptom of
	 * thresholds so permissive that the detector is tracing noise. Never stops the work it watches.
	 */
	static final class SlowWatchdog {
		private final javax.swing.Timer timer;		// Swing timer fires on the EDT, fine for a message
		SlowWatchdog(int armMillis, Runnable onSlow){
			timer = new javax.swing.Timer(armMillis, e -> onSlow.run());
			timer.setRepeats(false);
		}
		void arm(){ timer.restart(); }
		void disarm(){ timer.stop(); }
	}
	
	/** Utility class - static methods only; never instantiated. */
	private RidgeSegmenter() { }
}
