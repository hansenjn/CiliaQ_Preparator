package ciliaQ_Prep_jnh.ridge_detection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * CiliaQ Preparator - clean-up rules for the lines of one plane (RIDGE DETECTION). Most rules act within a plane;
 * dropHairpins is the exception, run after all planes to release parallel pairs a nearby plane traces as one fold.
 *
 * Cilia are in most cases unbranched. Where Steger's lines of one plane meet, these rules decide which lines are
 * different filaments; such pairs are KEPT APART: the caller's joining step must never put them into one object.
 * The rules never delete or move a line; the kink rule only cuts a line at a sharp bend.
 *
 * The rules run in this order (the dialog shows it):
 *   kink      - a turn of at least kinkAngle between the direction over w before a point and over w after it, both
 *               arms at least 2 w long, cuts the line; the two pieces are kept apart (filaments do not kink).
 *   branch    - a line that ends on another line's side (within 1.5 px of it, at least w from that line's ends) is
 *               kept apart from it.
 *   crossing  - two lines that the overlap resolution traced through the same crossing share points there (within
 *               0.5 px, at least w from both lines' ends): kept apart.
 *   parallel  - two lines that run side by side, 0.5-1.2 w apart and within 20 deg, over at least 2 w of consecutive
 *               points, are kept apart - unless they are one folded filament (turn release, or a single line of a
 *               nearby plane running back along both: see dropHairpins).
 * Turn release: a parallel pair stays one filament when its runners connect around a turn - an end of one lies
 * within 1.5 w of the other (or of a short line whose other end touches the other) and the directions there differ
 * by at least turnAngle (over turnTangent line widths) - and the turn carries continuous signal: the brightest path
 * from the end to the other runner never drops below turnContinuity x the runners' ridge level.
 *
 * Lines are float[][] {x, y, response} with, if widths were estimated, two further rows {width left, width right}
 * carried along so a line the kink rule cut keeps its widths. Pairs are {line, line, type} with the types below.
 * One instance per detection run: the counters and the plane image are state of that run (not static), so two runs
 * in one Fiji session do not share them.
 *
 * @author Jan N. Hansen
 */
public class RidgeCleanup {

	public static final int KINK = 0, BRANCH = 1, PARALLEL = 2, CROSSING = 3;

	/** Rule switches and parameters, from RidgeSettings. */
	public double kinkAngle = 0;			// degrees; 0 = kink rule off
	public boolean branch = true, crossing = true, parallel = false, turnRelease = true;
	public double turnAngle = 25, turnTangent = 2, turnContinuity = 0.9;

	/** Which rules this call runs: 3D reads branch and crossing off the lines as 2D sees them, the rest off its own. */
	public boolean runLineRules = true, runOtherRules = true;

	/** Fixed constants of the rules, in line widths. */
	static final double KINK_ARM = 2, PAR_MIN = 0.5, PAR_MAX = 1.2, PAR_LEN = 2, TOUCH = 1.5, SHARE = 0.5, CONTINUITY_BLUR = 1;

	/** Counters for the log. */
	public int nKink, nBranch, nCross, nPar, nHairpin;

	private float[] img;	// the plane, smoothed by CONTINUITY_BLUR, for the turn release's continuity test
	private int iw, ih;

	public RidgeCleanup() { super(); }

	public boolean any() { return kinkAngle > 0 || branch || crossing || parallel; }

	/** The plane the next apply() works on; needed only by the turn release. */
	public void setPlane(ImageProcessor ip) {
		if (!(parallel && turnRelease)) { img = null; return; }
		FloatProcessor f = (FloatProcessor) ip.convertToFloat().duplicate();
		if (CONTINUITY_BLUR > 0) f.blurGaussian(CONTINUITY_BLUR);
		img = (float[]) f.getPixels(); iw = f.getWidth(); ih = f.getHeight();
	}

	/** The lines after the kink cuts; apart receives index pairs into the returned list. */
	public List<float[][]> apply(List<float[][]> lines, double w, List<int[]> apart) {
		List<float[][]> out = kinks(lines, w, apart);
		if (branch && runLineRules) branches(out, w, apart);
		if (crossing && runLineRules) crossings(out, w, apart);
		if (parallel && runOtherRules) parallels(out, w, apart);
		return out;
	}

	// ------------------------------------------------------------------ the rules

	List<float[][]> kinks(List<float[][]> lines, double w, List<int[]> apart) {
		List<float[][]> out = new ArrayList<>();
		for (float[][] L : lines) {
			List<float[][]> pcs = kinkAngle > 0 && runOtherRules ? cutKinks(L, w) : Collections.singletonList(L);
			for (int k = 0; k < pcs.size(); k++) {
				if (k > 0) apart.add(new int[] { out.size() - 1, out.size(), KINK });
				out.add(pcs.get(k));
			}
		}
		return out;
	}

	void branches(List<float[][]> out, double w, List<int[]> apart) {
		Map<Long, List<int[]>> grid = grid(out);
		for (int a = 0; a < out.size(); a++) {
			float[][] L = out.get(a); int n = L[0].length; if (n == 0) continue;
			for (int e = 0; e < 2; e++) {
				int q = e == 0 ? 0 : n - 1;
				int on = sideOf(out, grid, a, L[0][q], L[1][q], w);
				if (on < 0) continue;
				apart.add(new int[] { a, on, BRANCH }); nBranch++;
			}
		}
	}

	void crossings(List<float[][]> out, double w, List<int[]> apart) {
		Map<Long, List<int[]>> grid = grid(out); Set<Long> done = new HashSet<>();
		for (int a = 0; a < out.size(); a++) {
			float[][] L = out.get(a); int n = L[0].length;
			for (int i = 0; i < n; i++) {
				if (i < w || n - 1 - i < w) continue;
				for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
					for (int[] e : grid.getOrDefault(cell(L[0][i] + 4 * dx, L[1][i] + 4 * dy), Collections.<int[]>emptyList())) {
						if (e[0] <= a)
							continue;
						float[][] M = out.get(e[0]);
						int m = M[0].length;
						if (e[1] < w || m - 1 - e[1] < w)
							continue;
						if (Math.hypot(M[0][e[1]] - L[0][i], M[1][e[1]] - L[1][i]) > SHARE)
							continue;
						if (done.add(((long) a << 32) | e[0])) {
							apart.add(new int[] { a, e[0], CROSSING });
							nCross++;
						}
					}
			}
		}
	}

	void parallels(List<float[][]> out, double w, List<int[]> apart) {
		Map<Long, List<int[]>> grid = grid(out); int need = (int) Math.ceil(PAR_LEN * w);
		for (int a = 0; a < out.size(); a++) if (out.get(a)[0].length >= need) parallelsOf(out, grid, a, w, need, apart);
	}

	/** the parallel pairs of line a with the lines after it */
	void parallelsOf(List<float[][]> out, Map<Long, List<int[]>> grid, int a, double w, int need, List<int[]> apart) {
		float[][] L = out.get(a);
		Map<Integer, int[]> run = parallelRuns(out, grid, a, w); // other line -> {current run, best run, last index}
		for (Map.Entry<Integer, int[]> r : run.entrySet()) {
			if (r.getValue()[1] < need || a >= r.getKey())
				continue;
			if (turnRelease && turnJoins(L, out.get(r.getKey()), out, a, r.getKey(), w)) {
				nHairpin++;
				continue;
			}
			apart.add(new int[] { a, r.getKey(), PARALLEL });
			nPar++;
		}
	}

	/**
	 * @return other line -> {current run, best run, last index}
	 */
	Map<Integer, int[]> parallelRuns(List<float[][]> out, Map<Long, List<int[]>> grid, int a, double w) {
		double dmin = PAR_MIN * w, dmax = PAR_MAX * w, cos20 = Math.cos(Math.toRadians(20));
		float[][] L = out.get(a);
		int n = L[0].length;
		Map<Integer, int[]> run = new HashMap<>(); // other line -> {current run, best run, last index}
		final int gr = (int) Math.ceil(dmax / 4); // cells of 4 px: +-gr cells reach dmax
		for (int i = 0; i < n; i++) {
			double[] t = tan(L, i, w);
			if (t == null)
				continue;
			Map<Integer, Double> best = nearestPerLine(out, grid, a, L[0][i], L[1][i], gr);
			for (Map.Entry<Integer, Double> b : best.entrySet()) {
				double d = b.getValue();
				if (d < dmin || d > dmax)
					continue;
				float[][] M = out.get(b.getKey());
				int j = nearestIdx(M, L[0][i], L[1][i]);
				double[] u = tan(M, j, w);
				if (u == null || Math.abs(t[0] * u[0] + t[1] * u[1]) < cos20)
					continue;
				int[] r = run.computeIfAbsent(b.getKey(), k -> new int[] { 0, 0, -10 });
				r[0] = i - r[2] <= 3 ? r[0] + (i - r[2]) : 1;
				r[2] = i;
				r[1] = Math.max(r[1], r[0]);
			}
		}
		return run;
	}

	/**
	 * for one point (x, y) of line a: the distance to the nearest point of each
	 * other line within +-gr grid cells of 4 px
	 */
	Map<Integer, Double> nearestPerLine(List<float[][]> out, Map<Long, List<int[]>> grid, int a, float x, float y,
			int gr) {
		Map<Integer, Double> best = new HashMap<>();
		for (int dx = -gr; dx <= gr; dx++)
			for (int dy = -gr; dy <= gr; dy++)
				for (int[] e : grid.getOrDefault(cell(x + 4 * dx, y + 4 * dy), Collections.<int[]>emptyList())) {
					if (e[0] == a)
						continue;
					float[][] M = out.get(e[0]);
					double d = Math.hypot(M[0][e[1]] - x, M[1][e[1]] - y);
					if (d < best.getOrDefault(e[0], Double.MAX_VALUE))
						best.put(e[0], d);
				}
		return best;
	}

	/**
	 * Turn release: decides whether the two runners A and B of a parallel pair are in fact one filament that folds
	 * back on itself around a sharp turn, rather than two separate filaments running side by side.
	 *
	 * <p>MECHANISM. A folded filament leaves one runner, turns, and returns as the other runner; at the turn the two
	 * runners are joined by a short, sharply bent bridge of bright signal. This method looks for exactly that bridge.
	 * It tries each runner as the "departing" runner P (the other being the "target" runner Q) and each of P's two
	 * ends, i.e. four (P-end) combinations in total. For a combination to confirm a fold, three conditions must all
	 * hold:
	 * <ol>
	 *   <li>PROXIMITY - P's end reaches Q (directly, or via a short connecting line C), within the touch distance
	 *       J = TOUCH * w.</li>
	 *   <li>TURN - the path actually bends: the outward direction at P's end and the direction at the landing point
	 *       differ by at least turnAngle. Directions are unit vectors, so their turn is measured by the dot product;
	 *       |tp . tq| <= cos(turnAngle) means the (undirected) angle between them is >= turnAngle. The absolute value
	 *       makes the test blind to a 180 deg flip, since the two runners are traced in opposite senses.</li>
	 *   <li>CONTINUITY - the signal does not break across the turn: the brightest path from P's end to the landing
	 *       point never dips below turnContinuity x the runners' ridge level (see continuity()).</li>
	 * </ol>
	 * If any one combination satisfies all three, the pair is a fold and is NOT kept apart (returns true).
	 *
	 * <p>TWO LANDING CASES.
	 * <ul>
	 *   <li>DIRECTLY ONTO Q - P's end lands within J of the nearest point j on Q. The turn is measured between P's
	 *       outward direction and Q's tangent at j; continuity runs from P's end to Q[j].</li>
	 *   <li>VIA A SHORT BRIDGE C - at a broad turn Steger may trace the apex as a separate short line. So the method
	 *       also accepts P's end landing on one end of a short line C (length m in points, 2 <= m <= 3w) whose OTHER
	 *       end qf in turn touches Q (within J). The turn is then measured between P's outward direction and C's
	 *       tangent, and continuity runs from P's end to where C meets Q.</li>
	 * </ul>
	 *
	 * <p>Tangent and outward directions are taken over turnTangent line widths (turnTangent * w), long enough to be
	 * stable against single-point jitter. Runners shorter than 3 points carry no usable direction and are skipped.
	 *
	 * @param A, B   the two runners of the parallel pair, as rows {x, y, response, ...}
	 * @param out    all lines of the plane (needed to look for a short connecting line C)
	 * @param ia, ib the indices of A and B in out (so C can exclude the runners themselves)
	 * @param w      the line width in px (sets the touch distance and the tangent length)
	 * @return true if the pair is one folded filament (release it), false to keep the pair apart
	 */
	boolean turnJoins(float[][] A, float[][] B, List<float[][]> out, int ia, int ib, double w) {
		double J = TOUCH * w, cosL = Math.cos(Math.toRadians(turnAngle));
		for (int side = 0; side < 2; side++) {
			float[][] P = side == 0 ? A : B, Q = side == 0 ? B : A;
			int iq = side == 0 ? ib : ia, ip = side == 0 ? ia : ib;
			int n = P[0].length;
			if (n < 3)
				continue;
			for (int e = 0; e < 2; e++) {
				int q = e == 0 ? 0 : n - 1; double[] tp = outward(P, q, turnTangent * w); if (tp == null) continue;
				float x = P[0][q], y = P[1][q];
				// onto Q itself
				int j = nearestIdx(Q, x, y);
				if (Math.hypot(Q[0][j] - x, Q[1][j] - y) <= J) {
					double[] tq = tan(Q, j, turnTangent * w);
					if (tq != null && Math.abs(tp[0] * tq[0] + tp[1] * tq[1]) <= cosL
							&& continuity(P, q, Q, j, w) >= turnContinuity)
						return true;
				}
				// onto a short connecting line C whose OTHER end touches Q; the continuity runs from P's end to where C meets Q
				for (int lc = 0; lc < out.size(); lc++) {
					if (lc == ip || lc == iq)
						continue;
					float[][] C = out.get(lc);
					int m = C[0].length;
					if (m < 2 || m > 3 * w)
						continue;
					int k = nearestIdx(C, x, y);
					if (Math.hypot(C[0][k] - x, C[1][k] - y) > J)
						continue;
					int qf = Math.hypot(C[0][0] - x, C[1][0] - y) <= Math.hypot(C[0][m - 1] - x, C[1][m - 1] - y)
							? m - 1
							: 0;
					int jq = nearestIdx(Q, C[0][qf], C[1][qf]);
					if (Math.hypot(Q[0][jq] - C[0][qf], Q[1][jq] - C[1][qf]) > J)
						continue;
					double[] tc = tan(C, k, turnTangent * w);
					if (tc == null)
						continue;
					if (Math.abs(tp[0] * tc[0] + tp[1] * tc[1]) <= cosL && continuity(P, q, Q, jq, w) >= turnContinuity)
						return true;
				}
			}
		}
		return false;
	}

	/**
	 * After all planes: drop parallel pairs whose two lines a single line of a nearby plane (up to 2 away) runs along, with
	 * different points (at least w within w/2 of each, at most 20 % shared) and in opposite directions - the traced U-turn
	 * of one folded filament. A line between two merged filaments lies near both with the same points; a coiled pair's
	 * line keeps its direction.
	 */
	public void dropHairpins(List<List<float[][]>> linesZ, List<List<int[]>> pairsZ, double w) {
		int D = linesZ.size(); List<Map<Long, List<int[]>>> grids = new ArrayList<>();
		for (List<float[][]> L : linesZ) grids.add(grid(L));
		for (int z = 0; z < D; z++) {
			Iterator<int[]> it = pairsZ.get(z).iterator();
			while (it.hasNext()) {
				int[] p = it.next();
				if (p[2] != PARALLEL)
					continue;
				float[][] A = linesZ.get(z).get(p[0]), B = linesZ.get(z).get(p[1]);
				boolean fold = false;
				for (int z2 = Math.max(0, z - 2); z2 <= Math.min(D - 1, z + 2) && !fold; z2++) {
					if (z2 == z)
						continue;
					Map<Integer, Set<Integer>> na = near(A, grids.get(z2), linesZ.get(z2), 0.5 * w),
							nb = near(B, grids.get(z2), linesZ.get(z2), 0.5 * w);
					for (Map.Entry<Integer, Set<Integer>> e : na.entrySet()) {
						Set<Integer> sa = e.getValue(), sb = nb.get(e.getKey());
						if (sb == null || sa.size() < w || sb.size() < w)
							continue;
						int both = 0;
						for (int k : sa)
							if (sb.contains(k))
								both++;
						if (both > 0.2 * Math.min(sa.size(), sb.size()))
							continue;
						float[][] C = linesZ.get(z2).get(e.getKey());
						double[] ta = meanTan(C, sa, w), tb = meanTan(C, sb, w);
						if (ta != null && tb != null && ta[0] * tb[0] + ta[1] * tb[1] < -Math.cos(Math.toRadians(45))) {
							fold = true;
							break;
						}
					}
				}
				if (fold) {
					it.remove();
					nHairpin++;
				}
			}
		}
	}

	// ------------------------------------------------------------------ the continuity test of the turn release

	/**
	 * Continuity of signal across a turn: how bright the darkest point on the best path from runner P's end to point
	 * j of runner Q is, expressed as a fraction of the dimmer of the two runners' ridge levels.
	 *
	 * <p>A genuine fold keeps bright signal all the way around the turn, so the darkest point on the connecting path
	 * stays high; two unrelated filaments that merely pass close have a dark gap between them, so the darkest point
	 * drops. The caller compares the returned ratio against turnContinuity.
	 *
	 * <p>STEPS: take each runner's ridge level (ridge(), the median brightness along it); the reference level lo is
	 * the smaller of the two, so a dim runner is not asked to match a bright one. Line ends fade, so instead of P's
	 * exact endpoint q start from its first properly bright point (bright()). Then measure the bottleneck (darkest
	 * point) of the brightest path from there to Q[j] (bottleneck()), normalised to lo and capped at 1.
	 *
	 * <p>When no plane image is loaded (img == null, i.e. the turn release is off) this returns 1, so the continuity
	 * test never blocks a release that was not asked for.
	 *
	 * @param P, Q the two runners, rows {x, y, response, ...}
	 * @param q    the index of P's end the turn departs from
	 * @param j    the index on Q the turn lands on
	 * @param w    line width in px (how far in from the end bright() may look)
	 * @return the bottleneck brightness as a fraction (0..1) of the dimmer runner's ridge level
	 */
	double continuity(float[][] P, int q, float[][] Q, int j, double w) {
		if (img == null)
			return 1;
		double rp = ridge(P), rq = ridge(Q);
		int ip = bright(P, q, rp, w);
		return bottleneck(P[0][ip], P[1][ip], Q[0][j], Q[1][j], 1.5 * w, Math.min(rp, rq));
	}

	/**
	 * The first "properly bright" point at or just inside a line's end. A Steger line fades out at its tips, so its
	 * very last point sits on dim signal and is a poor place to start a continuity path from. Walking inwards from
	 * the end q (in the direction st: +1 from the start, -1 from the end), this returns the first point whose
	 * brightness in the smoothed plane reaches 80 % of the line's ridge level r (its median brightness).
	 *
	 * <p>If no point within the first ceil(w) points is that bright (a uniformly dim line), it falls back to the
	 * deepest point it looked at, so a usable index is always returned.
	 *
	 * @param L the line, rows {x, y, response, ...}
	 * @param q the end index to start from (0 or n-1)
	 * @param r the line's ridge level (see ridge())
	 * @param w line width in px (bounds how far inwards to look)
	 * @return the index of the first point reaching 80 % of r, or the deepest point searched
	 */
	int bright(float[][] L, int q, double r, double w) {
		int n = L[0].length, st = q == 0 ? 1 : -1;
		for (int k = 0; k <= Math.min(n - 1, (int) Math.ceil(w)); k++) {
			int i = q + st * k;
			if (img[clampY(L[1][i]) * iw + clampX(L[0][i])] >= 0.8 * r)
				return i;
		}
		return q + st * Math.min(n - 1, (int) Math.ceil(w));
	}

	/**
	 * the ridge level of a line: the median of the smoothed plane along its points
	 */
	double ridge(float[][] L) {
		int n = L[0].length;
		if (n == 0)
			return 0;
		float[] v = new float[n];
		for (int i = 0; i < n; i++)
			v[i] = img[clampY(L[1][i]) * iw + clampX(L[0][i])];
		Arrays.sort(v);
		return v[n / 2];
	}

	/**
	 * Widest-bottleneck (max-min) path brightness between two points, as a fraction of lo. Among all 8-connected
	 * pixel paths from near (ax, ay) to near (bx, by), each path's value is its DARKEST pixel (the bottleneck); this
	 * returns the largest such value over all paths - the brightest route's weakest link - divided by lo and capped
	 * at 1. A bright unbroken connection gives a value near or above lo (ratio ~1); a dark gap forces every route
	 * through a dim pixel and gives a low ratio.
	 *
	 * <p>ALGORITHM. A Dijkstra variant on the smoothed plane: instead of summing edge costs it propagates the
	 * running minimum brightness along a path and always expands the frontier pixel with the highest such minimum
	 * (a max-heap on that value). best[k] holds the best bottleneck found so far to pixel k; when a goal pixel is
	 * popped, its value is optimal and is returned immediately. The search is confined to the bounding box of the
	 * two endpoints grown by margin m, so it is local and cheap.
	 *
	 * <p>Start and goal are each snapped to the brightest pixel within 1 px of the respective endpoint (endpoints
	 * are sub-pixel). If the box is empty (both endpoints outside the image) or lo <= 0, there is no meaningful path
	 * and 0 is returned.
	 *
	 * @param ax, ay the start point (sub-pixel)
	 * @param bx, by the goal point (sub-pixel)
	 * @param m      margin in px added around the endpoints' bounding box
	 * @param lo     the brightness to normalise against (the dimmer runner's ridge level)
	 * @return the brightest path's bottleneck brightness as a fraction (0..1) of lo
	 */
	double bottleneck(double ax, double ay, double bx, double by, double m, double lo) {
		if (lo <= 0)
			return 0;
		int x0 = (int) Math.max(0, Math.floor(Math.min(ax, bx) - m)),
				x1 = (int) Math.min(iw - 1, Math.ceil(Math.max(ax, bx) + m));
		int y0 = (int) Math.max(0, Math.floor(Math.min(ay, by) - m)),
				y1 = (int) Math.min(ih - 1, Math.ceil(Math.max(ay, by) + m));
		if (x1 < x0 || y1 < y0)
			return 0; // both ends far outside the image: no path in it (a negative box size threw before)
		int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
		double[] best = new double[bw * bh];
		Arrays.fill(best, -Double.MAX_VALUE);
		boolean[] goal = new boolean[bw * bh];
		int sa = -1;
		double sv = -Double.MAX_VALUE; // start: brightest pixel within 1 px of A's end; goal: within 1 px of B's end
		for (int dy = -1; dy <= 1; dy++)
			for (int dx = -1; dx <= 1; dx++) {
				goal[boxIdx(bx + dx, by + dy, x0, y0, x1, y1, bw)] = true;
				int k = boxIdx(ax + dx, ay + dy, x0, y0, x1, y1, bw);
				double v = img[(y0 + k / bw) * iw + x0 + k % bw];
				if (v > sv) {
					sv = v;
					sa = k;
				}
			}
		PriorityQueue<double[]> pq = new PriorityQueue<>((u, v) -> Double.compare(v[0], u[0]));
		best[sa] = sv;
		pq.add(new double[] { sv, sa });
		while (!pq.isEmpty()) {
			double[] c = pq.poll();
			int k = (int) c[1];
			if (c[0] < best[k])
				continue;
			if (goal[k])
				return Math.min(1, c[0] / lo);
			int cx = k % bw, cy = k / bw;
			for (int dy = -1; dy <= 1; dy++)
				for (int dx = -1; dx <= 1; dx++) {
					if (dx == 0 && dy == 0)
						continue;
					int nx = cx + dx, ny = cy + dy;
					if (nx < 0 || ny < 0 || nx >= bw || ny >= bh)
						continue;
					int nk = ny * bw + nx;
					double v = Math.min(c[0], img[(y0 + ny) * iw + x0 + nx]);
					if (v > best[nk]) {
						best[nk] = v;
						pq.add(new double[] { v, nk });
					}
				}
		}
		return 0;
	}

	static int boxIdx(double x, double y, int x0, int y0, int x1, int y1, int bw) {
		int xi = (int) Math.max(x0, Math.min(x1, Math.round(x))),
			yi = (int) Math.max(y0, Math.min(y1, Math.round(y)));
		return (yi - y0) * bw + xi - x0;
	}

	int clampX(float x) {
		return Math.max(0, Math.min(iw - 1, Math.round(x)));
	}

	int clampY(float y) {
		return Math.max(0, Math.min(ih - 1, Math.round(y)));
	}

	// ------------------------------------------------------------------ geometry helpers

	/**
	 * Split a line at sharp bends. Cilia are smoothly curved, so a sudden kink usually means two different filaments
	 * were traced as one line through a junction; cutting there lets the caller keep the two pieces apart.
	 *
	 * <p>TURN PROFILE. At each interior point i the local turn angle is the angle between the chord arriving over kb
	 * points (from i-kb to i) and the chord leaving over kb points (from i to i+kb), where kb = round(w). With unit
	 * chord vectors u and v, turn = acos(u . v); clamping the dot product to [-1, 1] guards against floating-point
	 * drift pushing acos out of domain. A straight run gives ~0 deg, a hairpin ~180 deg.
	 *
	 * <p>CUT SELECTION. A point is cut only if its turn is a clear local peak of the profile that is also sharp and
	 * well-separated: turn[i] >= kinkAngle, turn[i] is a local maximum (>= its left neighbour and > its right one),
	 * it is more than 2*kb points past the previous cut, and both resulting arms are at least KINK_ARM * w (= 2w)
	 * points long - measured from the previous cut (or the line start) and to the line end. The arm-length rule
	 * stops a frayed tip from being chopped into slivers.
	 *
	 * <p>If no point qualifies the line is returned whole (as a single-element list); otherwise it is returned as
	 * the consecutive pieces between successive cuts, each carrying all rows (see sub()). nKink counts the cuts.
	 *
	 * @param L the line, rows {x, y, response, ...}
	 * @param w line width in px (sets the chord half-length kb and the minimum arm length)
	 * @return the line split into pieces at its kinks, or the line alone if it has none
	 */
	List<float[][]> cutKinks(float[][] L, double w) {
		float[] X = L[0], Y = L[1];
		int n = X.length, kb = Math.max(2, (int) Math.round(w)), arm = (int) Math.round(KINK_ARM * w);
		double[] turn = new double[n];
		for (int i = kb; i + kb < n; i++) {
			double ux = X[i] - X[i - kb], uy = Y[i] - Y[i - kb],
					vx = X[i + kb] - X[i], vy = Y[i + kb] - Y[i],
					nu = Math.hypot(ux, uy), nv = Math.hypot(vx, vy);
			if (nu > 0 && nv > 0)
				turn[i] = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ux * vx + uy * vy) / (nu * nv)))));
		}
		List<Integer> cuts = new ArrayList<>();
		int last = -1000;
		for (int i = kb; i + kb < n; i++)
			if (turn[i] >= kinkAngle && turn[i] >= turn[i - 1] && turn[i] > turn[i + 1] && i - last > 2 * kb
					&& i - (cuts.isEmpty() ? 0 : cuts.get(cuts.size() - 1)) >= arm && n - 1 - i >= arm) {
				cuts.add(i);
				last = i;
				nKink++;
			}
		if (cuts.isEmpty())
			return Collections.singletonList(L);
		List<float[][]> r = new ArrayList<>();
		int s = 0;
		for (int c : cuts) {
			r.add(sub(L, s, c));
			s = c + 1;
		}
		r.add(sub(L, s, n - 1));
		return r;
	}

	/** the line whose side (not within w of its ends) the point touches (within 1.5 px), or -1 */
	static int sideOf(List<float[][]> out, Map<Long, List<int[]>> grid, int self, float x, float y, double w) {
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int[] e : grid.getOrDefault(cell(x + 4 * dx, y + 4 * dy), Collections.<int[]>emptyList())) {
					if (e[0] == self)
						continue;
					float[][] M = out.get(e[0]);
					int m = M[0].length;
					if (e[1] < w || m - 1 - e[1] < w)
						continue;
					if (Math.hypot(M[0][e[1]] - x, M[1][e[1]] - y) <= TOUCH)
						return e[0];
				}
		return -1;
	}

	/** the outward direction at an end of a line, over h points (pointing past the end) */
	static double[] outward(float[][] L, int q, double w) {
		int n = L[0].length, h = Math.max(2, (int) Math.round(w)),
				r = q == 0 ? Math.min(n - 1, h) : Math.max(0, n - 1 - h);
		double x = L[0][q] - L[0][r], y = L[1][q] - L[1][r], d = Math.hypot(x, y);
		return d == 0 ? null : new double[] { x / d, y / d };
	}

	static double[] tan(float[][] L, int i, double w) {
		int n = L[0].length, h = Math.max(2, (int) Math.round(w / 2)), i0 = Math.max(0, i - h),
				i1 = Math.min(n - 1, i + h);
		double tx = L[0][i1] - L[0][i0], ty = L[1][i1] - L[1][i0], tn = Math.hypot(tx, ty);
		return tn == 0 ? null : new double[] { tx / tn, ty / tn };
	}

	static double[] meanTan(float[][] C, Set<Integer> idx, double w) {
		double x = 0, y = 0;
		for (int k : idx) {
			double[] t = tan(C, k, w);
			if (t != null) {
				x += t[0];
				y += t[1];
			}
		}
		double d = Math.hypot(x, y);
		return d == 0 ? null : new double[] { x / d, y / d };
	}

	/** per line of the other plane: the indices of its points within tol of a point of L */
	static Map<Integer, Set<Integer>> near(float[][] L, Map<Long, List<int[]>> g, List<float[][]> O, double tol) {
		Map<Integer, Set<Integer>> m = new HashMap<>();
		int r = (int) Math.ceil(tol / 4);
		for (int i = 0; i < L[0].length; i++)
			for (int dx = -r; dx <= r; dx++)
				for (int dy = -r; dy <= r; dy++)
					for (int[] e : g.getOrDefault(cell(L[0][i] + 4 * dx, L[1][i] + 4 * dy),
							Collections.<int[]>emptyList())) {
						float[][] M = O.get(e[0]);
						if (Math.hypot(M[0][e[1]] - L[0][i], M[1][e[1]] - L[1][i]) <= tol)
							m.computeIfAbsent(e[0], k -> new HashSet<>()).add(e[1]);
					}
		return m;
	}

	static int nearestIdx(float[][] M, float x, float y) {
		int b = 0;
		double bd = Double.MAX_VALUE;
		for (int j = 0; j < M[0].length; j++) {
			double d = Math.hypot(M[0][j] - x, M[1][j] - y);
			if (d < bd) {
				bd = d;
				b = j;
			}
		}
		return b;
	}

	static Map<Long, List<int[]>> grid(List<float[][]> out) {
		Map<Long, List<int[]>> g = new HashMap<>();
		for (int l = 0; l < out.size(); l++) {
			float[][] L = out.get(l);
			for (int i = 0; i < L[0].length; i++)
				g.computeIfAbsent(cell(L[0][i], L[1][i]), k -> new ArrayList<>()).add(new int[] { l, i });
		}
		return g;
	}

	static long cell(double x, double y) {
		return ((long) Math.floor(x / 4) << 32) | ((long) Math.floor(y / 4) & 0xffffffffL);
	}

	/**
	 * points a..b of a line, all rows (further rows, e.g. estimated widths, are copied too)
	 */
	static float[][] sub(float[][] L, int a, int b) {
		float[][] r = new float[L.length][b - a + 1];
		for (int c = 0; c < L.length; c++)
			System.arraycopy(L[c], a, r[c], 0, b - a + 1);
		return r;
	}
}
