/*
 * #%L
 * Ridge Detection plugin for ImageJ
 * %%
 * Copyright (C) 2014 - 2015 Thorsten Wagner (ImageJ java plugin), 1996-1998 Carsten Steger (original C code), 1999 R. Balasubramanian (detect lines code to incorporate within GRASP)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

package ciliaQ_Prep_jnh.ij_ridge_detector_steger;

import java.util.ArrayList;
import java.util.Arrays;

//import java.util.HashMap;
//import java.util.HashSet;
// CiliaQ-MOD: every HashSet/HashMap in this class that is ITERATED is a LinkedHashSet/LinkedHashMap.
// Line and Junction do NOT override hashCode(), so iterating a HashSet<Line> or HashMap<Line,...>
// follows identity-hash order, which varies between JVM runs - that made the original's detected
// lines non-reproducible on identical input. Insertion order is driven by deterministic loops over
// lines and junctions. The two plain HashSets left (jPts, visited in resolveByJunctionClusters) are
// only used for contains/add and never iterated.
//
// CiliaQ-MOD (modification 9): the merge step was rebuilt (resolve -> resolveByJunctionClusters). The
// original findOverlap, buildIntersectionMaps, both buildMergeList, buildResolvedList and fillArray,
// and the helpers only they used, were removed. See README.md, modification 9.


import java.util.List;
import java.util.Map;
import java.util.Set;

import ciliaQ_Prep_jnh.ij_ridge_detector_steger.LinesUtil.contour_class;
import ij.IJ;

/**
 * Resolve overlap between lines by selecting the fragment with the closest
 * matching slope (which is really just trying to preserve straightness -
 * implying that a straight passover is more likely than two overlapping turns).
 *
 * CiliaQ-MOD (modification 9): same principle, rebuilt merge step - see resolveByJunctionClusters.
 *
 * @author Mark Hiner (hinerm@gmail.com)
 * @author Jan Niklas Hansen (for CiliaQ-MOD modification 9)
 */
public class SlopeOverlapResolver extends AbstractOverlapResolver {

	/** CiliaQ-MOD (modification 9): nominal full line width in pixels, from the detection sigma; used for the overlap
	 *  limit when the lines carry no width (Estimate width off). <= 0 = unknown. */
	private final float nominalWidth;

	public SlopeOverlapResolver() { this(0); }

	/** CiliaQ-MOD (modification 9): sigma of the detection. Inverts the width -> sigma relation the CiliaQ
	 *  Preparator and the Ridge Detection plugin use, sigma = w / (2 sqrt 3) + 0.5. */
	public SlopeOverlapResolver(final double sigma) {
		this.nominalWidth = sigma > 0.5 ? (float) Math.max(1.0, 2.0 * Math.sqrt(3.0) * (sigma - 0.5)) : 0f;
	}

	/** CiliaQ-MOD (modification 10): a resolver for a known nominal full line width in pixels (the user's width). */
	public static SlopeOverlapResolver forWidth(final double width) {
		return new SlopeOverlapResolver((float) width);
	}

	private SlopeOverlapResolver(final float width) {
		this.nominalWidth = width > 0 ? width : 0f;
	}

	// TODO: make these constants configurable (e.g. via plugin parameters)
	
	/** The Constant SIGMA. */
	// Sigma for deciding if two floats are "close enough"
	private static final float SIGMA = 2.0f;

	// Length from junction point to use in line segment calculation.
	// Smaller distances are more subject to variance, while larger distances
	/** The Constant SLOPE_DIST. */
	// could introduce error due to actual curvature of line segments
	private static final int SLOPE_DIST = 5;

	// When considering the portion of a line segment to use in straightness
	// comparisons, we take the longest segment possible within the following
	/** The Constant STRAIGHT_TOLERANCE. */
	// tolerance. A value of "1" would require perfect straightness.
	private static final float STRAIGHT_TOLERANCE = 1.02f;

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * de.biomedical_imaging.ij.steger.OverlapResolver#resolve(de.biomedical_imaging
	 * .ij.steger.Lines, de.biomedical_imaging.ij.steger.Junctions, boolean)
	 */
	@Override
	public Lines resolve(final Lines originalLines, final Junctions junctions, final boolean verbose) {
		if (verbose) IJ.log("### Overlap detection using Slope heuristic (CiliaQ-MOD: junction clusters)");
		final Map<Line, Line> lineMap = new java.util.LinkedHashMap<Line, Line>();
		final Lines resolvedLines = resolveByJunctionClusters(originalLines, junctions, lineMap);
		final Set<Junction> updatedJunctions = updateJunctions(junctions, lineMap);
		pruneJunctions(junctions, updatedJunctions);
		updateContourClasses(updatedJunctions);
		return resolvedLines;
	}

	/** CiliaQ-MOD (modification 9): an overlap piece at a crossing is at most this many line widths long. Two
	 *  lines of width w crossing at angle a overlap over about w / sin(a/2): 1.4 w at 90 deg, 2.6 w at 45,
	 *  3.9 w at 30. Shallower crossings are not traced through. */
	private static final float OVERLAP_WIDTHS = 4f;

	/** CiliaQ-MOD (modification 9): a joint may bend by at most this angle (degrees); more means two different
	 *  filaments (a U-turn is 180, a T's side branch 90). Rather two pieces than one wrong merge. */
	private static final double MAX_BEND_DEG = 60;

	/*
	 * CiliaQ-MOD (modification 9): merge lines through junctions, rebuilt around junction CLUSTERS.
	 *
	 * The original treated every line with a junction at both ends as an "area of overlap" and paired
	 * its start and end neighbours straight through it - right for the few-pixel piece shared by two
	 * crossing filaments, wrong for a long line: for one plain ridge with stubs at both tips it built
	 * stub-ridge-stub paths (three copies of the ridge), and in a tangle it routed one filament along a
	 * second to a third. Its n-way grouping then chained lines transitively, which only held because
	 * those long lines were filtered out first. And the merges from both builders were emitted
	 * independently, so a line used twice came out twice.
	 *
	 * Here: (1) line ends lying on the same junction form a cluster; (2) a line joining two clusters and
	 * at most OVERLAP_WIDTHS line widths long is the overlap piece of a crossing - it may be shared by the
	 * filaments that cross through it; (3) joints are proposed between line ends of one cluster, and
	 * through an overlap piece between its two clusters, scored with the straightCalc measure the
	 * original used, over the path the joint builds; (4) joints are accepted straightest first, one
	 * partner per line end, no cycles, at most two filaments through one overlap piece, and only if the
	 * path bends by at most MAX_BEND_DEG; (5) accepted joints are walked into chains. Every line other
	 * than an overlap piece ends up in exactly one output line.
	 */
	private Lines resolveByJunctionClusters(final Lines lines, final Junctions junctions, final Map<Line, Line> lineMap) {
		// --- 1. junction ends, and their clusters
		
		//	1a. Collect all junction coordinates into a lookup set.
		//		Key = the two float coords encoded as exact int bits ("x,y"), so comparison is bit-exact
		//		(avoids float == float rounding issues).
		
		final Set<String> jPts = new java.util.HashSet<String>();
		for (final Junction j : junctions) {
			jPts.add(Float.floatToIntBits(j.getX()) + "," + Float.floatToIntBits(j.getY()));
		}
		
		//	1b. Find every LINE END that sits exactly on a junction.
		//		For each line we check both ends (side 0 = first point, side 1 = last point).
		//		eLine[i] + eSide[i] together identify one such "line end".
		final List<Line> eLine = new ArrayList<Line>();
		final List<Integer> eSide = new ArrayList<Integer>();
		for (final Line l : lines) for (int side = 0; side < 2; side++) {
			final float[] p = getPoint(l, side == 0 ? 0 : l.getNumber() - 1);
			if (jPts.contains(Float.floatToIntBits(p[0]) + "," + Float.floatToIntBits(p[1]))) { eLine.add(l); eSide.add(side); }
		}
		
		// number of junction-touching line ends
		final int nE = eLine.size();
		
		//	1c. Union-find (disjoint set) over those line ends.
		//		cl[i] points to another end in the same cluster; following cl[] until cl[r] == r
		//		gives the cluster's representative ("root"). Two ends that lie at (nearly) the same
		//		point get merged into one cluster.
		final int[] cl = new int[nE];
		for (int i = 0; i < nE; i++) cl[i] = i;   // start: every end is its own cluster
		
		// Compare every pair of ends; if they coincide (within SIGMA), union their clusters.
		for (int i = 0; i < nE; i++) for (int k = i + 1; k < nE; k++) {
			if (eLine.get(i) == eLine.get(k)) continue;   // don't merge a line with itself
			
			if (intersects(endPoint(eLine.get(i), eSide.get(i)), endPoint(eLine.get(k), eSide.get(k)), SIGMA)) {
				int a = i, b = k;
				
				// find root of i
				while (cl[a] != a) a = cl[a];
				
				// find root of k
				while (cl[b] != b) b = cl[b];
				
				// merge: point the larger root at the smaller (keeps a stable representative)
				if (a != b) cl[Math.max(a, b)] = Math.min(a, b);
			}
		}
		
		//	1d. Materialise the clusters.
		//		clusters:   root -> list of end-indices belonging to that cluster.
		//		clusterOf:  line -> [cluster of its start end, cluster of its end end]; -1 = that end
		//					is not on any junction.
		final Map<Integer, List<Integer>> clusters = new java.util.LinkedHashMap<Integer, List<Integer>>();
		final Map<Line, int[]> clusterOf = new java.util.LinkedHashMap<Line, int[]>();	// [start cluster, end cluster], -1 = none
		for (int i = 0; i < nE; i++) {
			// resolve end i to its cluster root
			int r = i; while (cl[r] != r) r = cl[r];

			// add this end to its cluster's member list
			if (!clusters.containsKey(r)) clusters.put(r, new ArrayList<Integer>());
			clusters.get(r).add(i);

			// record which cluster this line's start/end sits in
			if (!clusterOf.containsKey(eLine.get(i))) clusterOf.put(eLine.get(i), new int[] { -1, -1 });
			clusterOf.get(eLine.get(i))[eSide.get(i)] = r;
		}
		
		// --- 2. overlap pieces
		// A line counts as an "overlap piece" (a short redundant bridge between two junctions)
		// if BOTH its ends sit on junction clusters, those clusters are DIFFERENT, and the line
		// is short (length <= OVERLAP_WIDTHS line-widths). These get skipped during joint-building.
		final Set<Line> overlap = new java.util.LinkedHashSet<Line>();
		
		// Flag short bridge lines between two different junctions as overlap pieces (skipped later).
		for (final Map.Entry<Line, int[]> e : clusterOf.entrySet()) {
			final int[] c = e.getValue();   // c[0] = start-end cluster, c[1] = end-end cluster (-1 = free)
			if (c[0] >= 0 && c[1] >= 0															// both ends on junction
					&& c[0] != c[1]																// spans two junctions
					&& e.getKey().estimateLength() <= OVERLAP_WIDTHS * lineWidth(e.getKey()))	// is short
				overlap.add(e.getKey());
		}
		
		// --- 3. candidate joints
		//	A "joint" is one proposed connection between two line ends. It is stored across six parallel
		//	lists, all indexed by the same joint number o:
		//	  jA[o], jB[o]   = the two lines being joined
		//	  jEA[o], jEB[o] = which END of each (0 = start, 1 = end) the joint attaches to
		//	  jIns[o]        = the overlap piece the joint passes through, or null if the ends simply meet
		//	  jScore[o]      = straightness of the resulting path (lower = straighter, so better)
		final List<Line> jA = new ArrayList<Line>(), jB = new ArrayList<Line>(), jIns = new ArrayList<Line>();
		final List<Integer> jEA = new ArrayList<Integer>(), jEB = new ArrayList<Integer>();
		final List<Float> jScore = new ArrayList<Float>();
		for (final List<Integer> members : clusters.values()) {			// within one cluster
			for (int x = 0; x < members.size(); x++) for (int y = x + 1; y < members.size(); y++) {
				final Line a = eLine.get(members.get(x)), b = eLine.get(members.get(y));
				
				// skip if same line, or if either line was already used in another joint
				if (a == b || overlap.contains(a) || overlap.contains(b)) continue;
				
				final float[] pa = endPoint(a, eSide.get(members.get(x))), pb = endPoint(b, eSide.get(members.get(y)));
				
				//	ia, ib: a point a short way INTO each line from its end, giving the direction the line
				//	arrives from. pa->ia and pb->ib are the two approach directions; bendDeg compares them.
				final float[] ia = getInterceptPoint(pa, a), ib = getInterceptPoint(pb, b);
				
				// if turn to sharp defined by MAX_BEND_DEG variable (0 = straight, 180 = U-turn) -> reject this connection
				if (bendDeg(ia, pa, pb, ib) > MAX_BEND_DEG) continue;
				
				// register this joint between segments a and b, with its straightness score
				addJoint(jA, jEA, jB, jEB, jIns, jScore, a, eSide.get(members.get(x)), b, eSide.get(members.get(y)), null,
						straightCalc(ia, pa, pb, ib));
			}
		}
		
		// Candidate joints that pass THROUGH an overlap piece: connect a line arriving at one
		// side of the bridge (cluster c[0]) to a line arriving at the other side (cluster c[1]).
		// jIns = ov records the bridge; straightness is measured across it (via p0, p1).
		for (final Line ov : overlap) {
			final int[] c = clusterOf.get(ov);         											// c[0], c[1] = the two clusters it links			
			final float[] p0 = endPoint(ov, 0), p1 = endPoint(ov, 1);			// the bridge's two endpoints
			
			// pair every line end in cluster c[0] with every line end in cluster c[1]
			for (final Integer s : clusters.get(c[0])) for (final Integer t : clusters.get(c[1])) {
				final Line a = eLine.get(s), b = eLine.get(t);									// line arriving at the c[0] side (a) and c
				
				// Prohibit self-join, no overlap pieces as endpoints
				if (a == b || overlap.contains(a) || overlap.contains(b)) continue;

				// approach directions into a and b
				final float[] ia = getInterceptPoint(endPoint(a, eSide.get(s)), a), ib = getInterceptPoint(endPoint(b, eSide.get(t)), b);
				
				// reject if the overall turn through the bridge is too sharp
				if (bendDeg(ia, endPoint(a, eSide.get(s)), endPoint(b, eSide.get(t)), ib) > MAX_BEND_DEG) continue;
				
				// register the joint, recording ov as the piece it passes through
				addJoint(jA, jEA, jB, jEB, jIns, jScore, a, eSide.get(s), b, eSide.get(t), ov,
						straightCalc(ia, p0, p1, ib));
			}
		}
		
		// --- 4. accept straightest first
		// Greedy selection: sort all candidate joints by straightness (best first), then walk the
		// list and accept each joint only if it violates none of the rules below. Accepting a joint
		// links two line ends into a longer filament chain. Three data structures track state:
		//   order[]   = joint indices sorted straightest-first (stable, so ties keep proposal order)
		//   joint     = line -> [joint claiming its start end, joint claiming its end end]
		//   root      = union-find over LINES, to detect joints that would close a loop
		//   carried   = overlap piece -> how many filaments already pass through it
		
		final Integer[] order = new Integer[jA.size()];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, new java.util.Comparator<Integer>() {		// stable: ties keep proposal order
			public int compare(final Integer p, final Integer q) { return Float.compare(jScore.get(p), jScore.get(q)); }
		});
		final Map<Line, Integer[]> joint = new java.util.LinkedHashMap<Line, Integer[]>();	// [joint at start, joint at end]
		final Map<Line, Line> root = new java.util.LinkedHashMap<Line, Line>();
		final Map<Line, Integer> carried = new java.util.LinkedHashMap<Line, Integer>();	// filaments through each overlap piece
		
		//	Walk joints straightest-first and accept each one unless it breaks a rule. joint[line][end]
		//	records which joint (if any) already claimed that end; root is a second union-find, over lines,
		//	to detect and reject joints that would close a loop.
		for (final Integer o : order) {
			final Line a = jA.get(o), b = jB.get(o), ins = jIns.get(o);
			final int ea = jEA.get(o), eb = jEB.get(o);
			if (!joint.containsKey(a)) { joint.put(a, new Integer[2]); root.put(a, a); }
			if (!joint.containsKey(b)) { joint.put(b, new Integer[2]); root.put(b, b); }
			if (joint.get(a)[ea] != null || joint.get(b)[eb] != null) continue;				// rule: one partner per line end
			if (ins != null && carried.containsKey(ins) && carried.get(ins) >= 2) continue;	// rule: a crossing is two filaments, so at most two through one overlap piece
			Line ra = a, rb = b;
			while (root.get(ra) != ra) ra = root.get(ra);	// find root of a's chain
			while (root.get(rb) != rb) rb = root.get(rb);	// find root of b's chain
			if (ra == rb) continue;							// rule: already in the same chain -> would form a cycle
			
			// accept: all rules passed, so commit the joint
			root.put(ra, rb);								// accept: merge the two chains
			if (ins != null) carried.put(ins, carried.containsKey(ins) ? carried.get(ins) + 1 : 1);
			joint.get(a)[ea] = o;							// claim a's end so nothing else can use it
			joint.get(b)[eb] = o;                           // claim b's end likewise
		}
		
		// --- 5. chains
		final Set<Line> finalLines = new java.util.LinkedHashSet<Line>(lines);
		final Set<Line> visited = new java.util.HashSet<Line>();
		final List<Line> merged = new ArrayList<Line>();
		
		//	Each accepted chain is walked from one of its two free ends to the other, collecting the lines
		//	(and any overlap pieces between them) in order, with a reverse flag so concatenate can lay them
		//	head-to-tail. Start only at a chain END - a line with a joint on exactly one side.
		for (final Line t : lines) {
			final Integer[] tj = joint.get(t);
			
			//	Skip: not in any chain, already walked, or joined on BOTH sides (a middle line, not an end).
			if (tj == null || visited.contains(t) || (tj[0] == null) == (tj[1] == null)) continue;
			
			final List<Line> parts = new ArrayList<Line>();
			final List<Boolean> rev = new ArrayList<Boolean>();	// per part: lay it reversed?
			Line cur = t;
			int entry = tj[0] == null ? 0 : 1;	// the end we came IN by (the free end has no joint)
			while (true) {
				visited.add(cur);
				parts.add(cur); rev.add(entry == 1);			// entered at the end -> reverse so it reads start..end
				final int exit = 1 - entry;						// leave by the opposite end
				final Integer o = joint.get(cur)[exit];
				
				if (o == null) break;							// no joint here: this is the far end of the chain
				
				//	The joint stores its two lines as a and b; find which one is cur, so next is the other.
				final boolean curIsA = jA.get(o) == cur && jEA.get(o) == exit;
				final Line next = curIsA ? jB.get(o) : jA.get(o);
				final int nextEnd = curIsA ? jEB.get(o) : jEA.get(o);	// the end of next this joint attaches to
				final Line ins = jIns.get(o);
				if (ins != null) {								// insert the overlap piece, oriented from cur's side to next's
					final boolean insStartAtCur = clusterOf.get(ins)[0] == clusterOf.get(cur)[exit];
					parts.add(ins); rev.add(!insStartAtCur);
				}
				cur = next; entry = nextEnd;					// step onto next; we entered it at nextEnd
			}
			if (parts.size() < 2) continue;						// nothing was joined
			final Line m = concatenate(parts, rev);
			
			//	Map every consumed part to the new merged line (for junction updating), and drop the parts
			//	from the output set. An overlap piece carried by two chains is mapped by whichever is first.
			for (final Line l : parts) { if (!lineMap.containsKey(l)) lineMap.put(l, m); finalLines.remove(l); }
			merged.add(m);
		}
		finalLines.addAll(merged);
		final Lines out = new Lines(lines.getFrame());
		out.addAll(finalLines);
		return out;
	}

	/**
	 * Appends one candidate joint to the six parallel joint-lists (all indexed by the same
	 * joint number). A joint is a proposed connection between the end {@code ea} of line
	 * {@code a} and the end {@code eb} of line {@code b}, optionally passing through an
	 * overlap piece {@code ins}.
	 * <p>
	 * An invalid score ({@code NaN} or infinite) is replaced with {@link Float#MAX_VALUE} so
	 * the joint sorts last (worst) rather than corrupting the straightest-first ordering.
	 *
	 * @param jA     list of first lines; receives {@code a}
	 * @param jEA    list of first-line ends; receives {@code ea} (0 = start, 1 = end)
	 * @param jB     list of second lines; receives {@code b}
	 * @param jEB    list of second-line ends; receives {@code eb} (0 = start, 1 = end)
	 * @param jIns   list of overlap pieces; receives {@code ins} ({@code null} if the ends meet directly)
	 * @param jScore list of straightness scores; receives the (sanitised) {@code score}
	 * @param a      first line to join
	 * @param ea     which end of {@code a} the joint attaches to (0 = start, 1 = end)
	 * @param b      second line to join
	 * @param eb     which end of {@code b} the joint attaches to (0 = start, 1 = end)
	 * @param ins    overlap piece the joint passes through, or {@code null} if the ends meet directly
	 * @param score  straightness of the resulting path (lower = straighter); {@code NaN}/infinite is
	 *               clamped to {@link Float#MAX_VALUE}
	 */
	private static void addJoint(final List<Line> jA, final List<Integer> jEA, final List<Line> jB, final List<Integer> jEB,
			final List<Line> jIns, final List<Float> jScore, final Line a, final int ea,
			final Line b, final int eb, final Line ins, float score) {
		if (Float.isNaN(score) || Float.isInfinite(score)) score = Float.MAX_VALUE;
		jA.add(a); jEA.add(ea); jB.add(b); jEB.add(eb); jIns.add(ins); jScore.add(score);
	}
	
	/** The coordinates of one end of a line: side 0 = its first point, side 1 = its last point. */
	private float[] endPoint(final Line l, final int side) {
		return getPoint(l, side == 0 ? 0 : l.getNumber() - 1);
	}

	/** CiliaQ-MOD (modification 9): how much the path bends at a joint, in degrees.
	 *  Measures the angle between the approaching direction (u = endA - inA) and the
	 *  leaving direction (v = inB - endB), i.e. the ANGLE BETWEEN two "travel-direction"
	 *  vectors — NOT the interior joint angle.
	 *    0   = straight through (u and v parallel, same direction)
	 *    90  = right-angle turn
	 *    180 = U-turn (u and v antiparallel)
	 *  NOTE: this is the supplement of the conventional interior angle (where straight = 180).
	 *  Degenerate case: if either segment has zero length, returns 180 (treated as a U-turn). */
	private static double bendDeg(final float[] inA, final float[] endA, final float[] endB, final float[] inB) {
		final double ux = endA[0] - inA[0], uy = endA[1] - inA[1], vx = inB[0] - endB[0], vy = inB[1] - endB[1];
		final double nu = Math.hypot(ux, uy), nv = Math.hypot(vx, vy);
		if (nu == 0 || nv == 0) return 180;
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ux * vx + uy * vy) / (nu * nv)))));
	}

	/** Mean full line width as estimated by the detector; without an estimate (Estimate width off), the
	 *  nominal width from the detection sigma; 2 * SIGMA only if neither is known. */
	private float lineWidth(final Line l) {
		final float fallback = nominalWidth > 0 ? nominalWidth : 2 * SIGMA;
		if (l.width_l == null || l.width_r == null || l.width_l.length == 0) return fallback;
		double s = 0; int n = 0;
		for (int i = 0; i < l.width_l.length && i < l.width_r.length; i++) {
			final double w = l.width_l[i] + l.width_r[i];
			if (w > 0) { s += w; n++; }
		}
		return n == 0 ? fallback : (float) (s / n);
	}

	/**	Build one merged Line from the ordered parts. rev[i] says whether part i must be laid down
	 *	back-to-front so that the parts connect end-to-start and the merged line reads continuously. Each
	 *	per-point array (angle, width, ...) is copied in the same order. */
	private static Line concatenate(final List<Line> parts, final List<Boolean> rev) {
		int n = 0, frame = 0;
		for (final Line l : parts) { n += l.getNumber(); frame = l.getFrame(); }
		final Line m = new Line();
		m.angle = new float[n]; m.asymmetry = new float[n]; m.col = new float[n]; m.row = new float[n];
		m.response = new float[n]; m.intensity = new float[n]; m.width_l = new float[n]; m.width_r = new float[n];
		m.num = n;
		m.setContourClass(contour_class.cont_no_junc);
		m.setFrame(frame);
		int pos = 0;
		for (int i = 0; i < parts.size(); i++) {
			final Line l = parts.get(i); final boolean r = rev.get(i); final int len = l.getNumber();
			copyArray(l.angle, m.angle, pos, len, r); copyArray(l.asymmetry, m.asymmetry, pos, len, r);
			copyArray(l.col, m.col, pos, len, r); copyArray(l.row, m.row, pos, len, r);
			copyArray(l.response, m.response, pos, len, r); copyArray(l.intensity, m.intensity, pos, len, r);
			copyArray(l.width_l, m.width_l, pos, len, r); copyArray(l.width_r, m.width_r, pos, len, r);
			pos += len;
		}
		return m;
	}

	/**
	 * Copies {@code len} elements from {@code src} into {@code dst} starting at index
	 * {@code pos}, optionally in reverse order. If {@code src} is {@code null}, the target
	 * range is filled with zeros instead.
	 *
	 * @param src the source array, or {@code null} to zero-fill the target range
	 * @param dst the destination array (written in place)
	 * @param pos the starting index in {@code dst} to write to
	 * @param len the number of elements to copy
	 * @param rev if {@code true}, elements are copied in reverse order
	 *            ({@code src[len-1]} first); if {@code false}, in forward order
	 */
	private static void copyArray(final float[] src, final float[] dst, final int pos, final int len, final boolean rev) {
		if (src == null) { Arrays.fill(dst, pos, pos + len, 0f); return; }
		for (int i = 0; i < len; i++) dst[pos + i] = src[rev ? len - 1 - i : i];
	}

	/**
	 * Look through all {@link Junction}s. If either of the lines has been merged,
	 * the Junction is updated to reference the merged line. Returns the set of all
	 * such modified Junctions.
	 *
	 * @param junctions
	 *            the junctions
	 * @param lineMap
	 *            the line map
	 * @return the sets the
	 */
	private Set<Junction> updateJunctions(final Junctions junctions, final Map<Line, Line> lineMap) {
		final Set<Junction> updated = new java.util.LinkedHashSet<Junction>();	//CILIAQ-MOD for determinability

		for (final Junction junction : junctions) {
			Line mergedLine = null;
			if ((mergedLine = lineMap.get(junction.lineCont1)) != null) {
				junction.lineCont1 = mergedLine;
				junction.cont1 = mergedLine.getID();
				updated.add(junction);
			}
			mergedLine = null;
			if ((mergedLine = lineMap.get(junction.lineCont2)) != null) {
				junction.lineCont2 = mergedLine;
				junction.cont2 = mergedLine.getID();
				updated.add(junction);
			}
		}

		return updated;
	}

	/**
	 * Remove all redundant {@link Junction}s from the list. This includes Junctions
	 * with two references to the same line (because their lines were merged) and
	 * cases where multiple Junctions refer to the same lines as each other and
	 * occupy the same physical position.
	 *
	 * @param junctions
	 *            the junctions
	 * @param updatedJunctions
	 *            the updated junctions
	 */
	private void pruneJunctions(final Junctions junctions, final Set<Junction> updatedJunctions) {
		final Map<String, Junction> jMap = new java.util.LinkedHashMap<String, Junction>();	//CILIAQ-MOD for determinability
		for (final Junction j : updatedJunctions) {
			String key = null;
			// Remove Junctions with references to the same Line
			if (j.cont1 == j.cont2)
				junctions.remove(j);
			// Remove Junctions of the same two lines at the same x,y point
			else if (jMap.containsKey((key = getKey(j))))
				junctions.remove(j);
			else {
				// Keep the junction and register it as "the" definitive junction
				// for its two lines at this point.
				jMap.put(key, j);
			}
		}

		// Update the updatedJunctions set by removing all Junction instances
		// that have been removed from the master Junctions collection.
		updatedJunctions.retainAll(junctions);
	}

	/**
	 * For each {@link Junction} in the provided set, update the Junction's
	 * {@link Junction#pos}, {@link Junction#isNonTerminal}, and each line's
	 * {@link LinesUtil.contour_class} as appropriate.
	 *
	 * @param updatedJunctions
	 *            the updated junctions
	 */
	private void updateContourClasses(final Set<Junction> updatedJunctions) {
		for (final Junction j : updatedJunctions) {
			// process both lines.
			// For isNonTerminal to be updated, the junction point can't be on
			// either line's terminals.
			// We only update the pos of the Junction on the first line.
			j.isNonTerminal = processLine(j, j.lineCont1, true);
			j.isNonTerminal = processLine(j, j.lineCont2, false) && j.isNonTerminal;
		}
	}

	/**
	 * Iterate over the points of the line and find the pos of the junction If pos
	 * is 0 or line.length, update the line's contour class If this is the first
	 * line, set the Junction's pos to match If this junction doesn't sit on either
	 * line's terminals, set the Junction's isNonTerminal to true
	 *
	 * @param j
	 *            the j
	 * @param line
	 *            the line
	 * @param updatePos
	 *            - if true, update the pos of the given Junction
	 * @return True if the junction point was NOT on the start or end terminal of
	 *         the given line.
	 */
	private boolean processLine(final Junction j, final Line line, final boolean updatePos) {
		int pos = -1;
		final float[] x = line.getXCoordinates();
		final float[] y = line.getYCoordinates();
		// loop over all points of the line, or until we find the
		// point of intersection with the junction.
		for (int i = 0; i < line.num && pos < 0; i++) {
			if (Float.compare(x[i], j.x) == 0 && Float.compare(y[i], j.y) == 0)
				pos = i;
		}

		// update the Junction position if requested
		if (updatePos)
			j.pos = pos;

		// update contour class if appropriate
		// Nothing can supersede "cont_both_junc"
		if (!line.getContourClass().equals(contour_class.cont_both_junc)) {
			if (pos == 0) {
				// If this line is already an "end_junc", upgrade it to a "both"
				if (line.getContourClass().equals(contour_class.cont_end_junc)) {
					line.setContourClass(contour_class.cont_both_junc);
				}
				// Otherwise, set it as a "start_junc"
				else {
					line.setContourClass(contour_class.cont_start_junc);
				}
			} else if (pos == line.num - 1) {
				// If this line is already a "start_junc", upgrade it to a "both"
				if (line.getContourClass().equals(contour_class.cont_start_junc)) {
					line.setContourClass(contour_class.cont_both_junc);
				}
				// Otherwise, set it as a "end_junc"
				else {
					line.setContourClass(contour_class.cont_end_junc);
				}
			}
		}

		// Check the position of the junction within the line
		return !(pos == 0 || pos == line.num - 1);
	}

	/**
	 * Compute the striaghtness between the 3 given points. This is done by
	 * comparing distances: {@code ((p1 + p2) + (p2 + 3)) / (p3 + p1)} The closer
	 * the value is to 1, the straighter the line.
	 *
	 * @param points
	 *            the points
	 * @return the float
	 */
	private float straightCalc(final float[]... points) {
		float ideal = dist(points[0], points[points.length - 1]);
		float sum = 0;
		for (int i = 1; i < points.length; i++)
			sum += dist(points[i - 1], points[i]);

		return sum / ideal;
	}

	/**
	 * return the distance between 2 points.
	 *
	 * @param p1
	 *            the p 1
	 * @param p2
	 *            the p 2
	 * @return the float
	 */
	private float dist(final float[] p1, final float[] p2) {
		return (float) Math.sqrt(Math.pow((p2[0] - p1[0]), 2) + Math.pow((p2[1] - p1[1]), 2));
	}


	/**
	 * Helper method to get the point at {@code SLOPE_DIST} positions away from the
	 * intercept point between the query line and given point.
	 *
	 * @param p1
	 *            the p 1
	 * @param query
	 *            the query
	 * @return the intercept point
	 */
	private float[] getInterceptPoint(final float[] p1, final Line query) {
		int dist = 0;
		final List<float[]> points = new ArrayList<float[]>();
		points.add(p1);

		return findLongestPath(query, dist, points);
	}

	/**
	 * Recursive helper method. Iteratively searches to the next {@link #SLOPE_DIST}
	 * position in the query line. As long as this point maintains straightness
	 * within {@link #STRAIGHT_TOLERANCE}, and we haven't gone past the query line
	 * boundaries, recursion continues.
	 *
	 * @param query
	 *            the query
	 * @param dist
	 *            the dist
	 * @param points
	 *            the points
	 * @return the float[]
	 */
	private float[] findLongestPath(final Line query, int dist, final List<float[]> points) {
		// p1 is the original point to test
		final float[] p1 = points.get(0);
		// Incrementally check the next position
		dist += SLOPE_DIST;

		// pos is is the position in the query line to check
		int pos = 0;

		// Determine the position to check based on whether the query line intersects
		// with p1 at its start or end
		if (Math.abs(p1[0] - query.getXCoordinates()[0]) < SIGMA
				&& Math.abs(p1[1] - query.getYCoordinates()[0]) < SIGMA) {
			pos = Math.min(dist, query.getNumber() - 1);
		} else {
			pos = Math.max(0, query.getNumber() - 1 - dist);
		}

		final float[] p2 = new float[2];

		p2[0] = query.getXCoordinates()[pos];
		p2[1] = query.getYCoordinates()[pos];

		// if our position has reached the start (or end) of the query line,
		// return p2.
		if (pos == 0 || pos == query.getNumber() - 1) {
			return p2;
		}

		// If we still have a straight enough line, recurse to the next position
		points.add(p2);
		if (straightCalc(points.toArray(new float[points.size()][])) <= STRAIGHT_TOLERANCE) {
			return findLongestPath(query, dist, points);
		}

		// If the line is not straight enough, return the previous point.
		return points.get(points.size() - 2);
	}

	/**
	 * Intersects.
	 *
	 * @param tEnd
	 *            the t end
	 * @param qEnd
	 *            the q end
	 * @param threshold
	 *            the threshold
	 * @return if the distance between two points is within the given threshold
	 */
	private boolean intersects(final float[] tEnd, final float[] qEnd, final float threshold) {
		return Math.abs(qEnd[0] - tEnd[0]) < threshold && Math.abs(qEnd[1] - tEnd[1]) < threshold;
	}

	/**
	 * Builds a unique key identifying a given {@link Junction} based on the two
	 * associated lines, and the Junction's position.
	 *
	 * @param junction
	 *            the junction
	 * @return the key
	 */
	private String getKey(final Junction junction) {
		final StringBuilder sb = new StringBuilder();

		sb.append(Math.min(junction.cont1, junction.cont2));
		sb.append(Math.max(junction.cont1, junction.cont2));
		sb.append(junction.x);
		sb.append(junction.y);

		return sb.toString();
	}

	/**
	 * Helper method to return the x and y coordinates of the point at the specified
	 * index of a given line.
	 *
	 * @param target
	 *            the target
	 * @param i
	 *            the i
	 * @return the point
	 */
	private float[] getPoint(final Line target, final int i) {
		float[] coords = new float[2];
		coords[0] = target.getXCoordinates()[i];
		coords[1] = target.getYCoordinates()[i];
		return coords;
	}


}