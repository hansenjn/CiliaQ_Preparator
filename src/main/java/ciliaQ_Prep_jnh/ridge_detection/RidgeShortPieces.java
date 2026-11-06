package ciliaQ_Prep_jnh.ridge_detection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CiliaQ Preparator - step 6 of the ridge clean-up: short pieces next to a filament (RIDGE DETECTION).
 *
 * Runs last, on the finished objects (after all joins). An object A is a SHORT PIECE of an object B when
 *  - A comes within NEAR line widths of B (the nearest pair of their points),
 *  - B passes the minimum length, so it is kept on its own (a join never makes a new object out of two pieces that the
 *    minimum length would both remove),
 *  - B reaches at least LEN line widths from that nearest point of B (two short pieces side by side are left alone),
 *  - B is the nearest such object (a small piece beside another small piece at a tip still joins the filament), and
 *  - A reaches less than LEN line widths from that point.
 * Such pieces are typically the small forks Steger draws at a broad cilium tip, or a bit of a tip cut off. The caller
 * then joins each piece to its B (overriding the pairs that kept them apart), drops it, or keeps it (keep apart).
 *
 * Points are {x, y, z}, z in xy pixel units (plane x pixelDepth / pixelWidth). A z difference counts only beyond zTol:
 * in in-focus and 3D the in-focus positions along one filament scatter by about a plane. In-focus and 3D give each point
 * its in-focus z, so a standing cilium (short in xy, several planes high) reaches far and is never a piece; classic paints
 * every plane a line is seen in and passes z = 0 (xy only), so it cannot tell a standing cilium from a short piece.
 *
 * All pieces are decided on the objects as they are, so the result does not depend on the order of the joins. Ties go to
 * the nearer object, then to the lower id.
 *
 * Added in v0.2.1.
 *
 * @author Jan N. Hansen
 */
final class RidgeShortPieces {

	/** Modes (RidgeSettings.shortPieces). */
	static final int APART = 0, JOIN = 1, DROP = 2;

	/** In line widths: how far a piece may reach, and how near it must come to the object it belongs to. */
	static final double LEN = 2, NEAR = 1;

	private RidgeShortPieces() { super(); }

	/**
	 * @param obj object id -> its points {x, y, z}; iterated in ascending id
	 * @param kept the objects that pass the minimum length: only these can take a piece
	 * @param w the line width (px)
	 * @param zTol z difference (in xy pixel units) that does not count
	 * @return piece id -> id of the object it is a piece of
	 */
	static TreeMap<Integer, Integer> find(TreeMap<Integer, List<float[]>> obj, java.util.Set<Integer> kept, double w, double zTol) {
		final double lim = LEN * w, near = NEAR * w, cell = Math.max(1.0, near);
		Map<Long, List<int[]>> grid = new HashMap<Long, List<int[]>>(); // cell -> {object id, point index}
		for (Map.Entry<Integer, List<float[]>> e : obj.entrySet()) {
			List<float[]> ps = e.getValue();
			for (int i = 0; i < ps.size(); i++)
				grid.computeIfAbsent(key(ps.get(i), cell), k -> new ArrayList<int[]>())
						.add(new int[] { e.getKey(), i });
		}
		TreeMap<Integer, Integer> pieces = new TreeMap<Integer, Integer>();
		for (Map.Entry<Integer, List<float[]>> e : obj.entrySet()) {
			List<float[]> A = e.getValue();
			if (!couldBePiece(A, lim))
				continue;
			int b = pieceOf(e.getKey(), A, obj, kept, grid, near, lim, zTol, cell);
			if (b >= 0)
				pieces.put(e.getKey(), b);
		}
		return pieces;
	}

	/**
	 * The object A is a piece of: of the kept objects within near of A, the nearest one that reaches at least lim from the
	 * point where A comes nearest to it, if A reaches less than lim from that point; else -1. Several small pieces at one
	 * tip therefore each join the filament, not each other.
	 */
	static int pieceOf(int id, List<float[]> A, Map<Integer, List<float[]>> obj, java.util.Set<Integer> kept,
			Map<Long, List<int[]>> grid, double near, double lim, double zTol, double cell) {
		TreeMap<Integer, Object[]> nearest = new TreeMap<Integer, Object[]>(); // other object -> {distance, its nearest
																				// point}
		for (float[] p : A) {
			long cx = (long) Math.floor(p[0] / cell), cy = (long) Math.floor(p[1] / cell);
			for (long gx = cx - 1; gx <= cx + 1; gx++)
				for (long gy = cy - 1; gy <= cy + 1; gy++) {
					List<int[]> c = grid.get((gx << 32) | (gy & 0xffffffffL));
					if (c == null)
						continue;
					for (int[] r : c) {
						if (r[0] == id || !kept.contains(r[0]))
							continue;
						float[] o = obj.get(r[0]).get(r[1]);
						double d = dist(p, o, zTol);
						if (d > near)
							continue;
						Object[] n = nearest.get(r[0]);
						if (n == null || d < (Double) n[0])
							nearest.put(r[0], new Object[] { d, o });
					}
				}
		}
		List<Map.Entry<Integer, Object[]>> order = new ArrayList<Map.Entry<Integer, Object[]>>(nearest.entrySet());
		
		// stable: ties keep the lower id first
		order.sort((u, v) -> Double.compare((Double) u.getValue()[0], (Double) v.getValue()[0])); 

		for (Map.Entry<Integer, Object[]> n : order) {
			float[] q = (float[]) n.getValue()[1];
			if (!reachesLess(obj.get(n.getKey()), q, lim, zTol))
				return reachesLess(A, q, lim, zTol) ? n.getKey() : -1;
		}
		return -1;
	}

	/** an object whose xy extent is over 2 lim cannot lie within lim of one point */
	static boolean couldBePiece(List<float[]> A, double lim) {
		double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
		for (float[] p : A) {
			x0 = Math.min(x0, p[0]);
			x1 = Math.max(x1, p[0]);
			y0 = Math.min(y0, p[1]);
			y1 = Math.max(y1, p[1]);
		}
		return !A.isEmpty() && x1 - x0 < 2 * lim && y1 - y0 < 2 * lim;
	}

	/** true if every point of A lies less than lim from q */
	static boolean reachesLess(List<float[]> A, float[] q, double lim, double zTol) {
		for (float[] p : A)
			if (dist(p, q, zTol) >= lim)
				return false;
		return true;
	}

	/** distance, a z difference counting only beyond zTol */
	static double dist(float[] p, float[] q, double zTol) {
		double dx = p[0] - q[0], dy = p[1] - q[1], dz = Math.max(0, Math.abs(p[2] - q[2]) - zTol);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	static long key(float[] p, double cell) {
		return ((long) Math.floor(p[0] / cell) << 32) | ((long) Math.floor(p[1] / cell) & 0xffffffffL);
	}
}
