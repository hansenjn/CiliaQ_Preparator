package ciliaQ_Prep_jnh.expansion;

import java.util.ArrayList;
import java.util.Arrays;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

/**
 * CiliaQ Preparator - EXPANSION from seeds: one competitive-growth engine with a pluggable seed source.
 *
 * This expansion tool can work for both LoG seeds or any other high threshold seed
 * (hysteresis kind of seeding). So there is exactly ONE growth implementation here
 * and the seed source is a user choice.
 *
 * Seeds (choice)  ->  competitive growth inside (v > low)  ->  LABEL image
 * 
 * Seeds offered: high-threshold cores  |  spot maxima  |  ridge lines
 *
 * Note:
 *  - TOUCHING filaments separate.
 *  - CROSSING filaments do NOT separate, and no intensity-based seeding can make them.
 *    Where two cilia cross they overlap, so the crossing is the BRIGHTEST part of the object and the
 *    high-threshold cores stay connected exactly where the separation is needed.
 *
 * Mask components that contain no seed at all are given their own labels rather than dropped - without
 * that step the tool would silently delete objects the seed detector missed.
 * 
 * Added in V0.2.1.
 * 
 * @author Jan N. Hansen
 */
public class SeededGrowth {

	/** Result of one channel, one time point. */
	public static class Result {
		public int [] labels;			// W*H*D, 0 = background
		public int nLabels;
		public int nSeeds;
		public int nComponents;			// connected components of the low mask
		public int nSplit;				// components that kept more than one label
		public int nReverted;			// components the minimum-piece guard put back together
		public int foreground;			// voxels in the mask, for the lossless check
		public int nUnseeded;			// low-mask components that contained no seed
		public int unseededVoxels;		// their total size
		public boolean unseededDiscarded;
		public double lowUsed;
		public double seedThresholdUsed = Double.NaN;
	}

	private final int width, height, depth;
	private final int n; // = total voxel count of the 3D image = width * height * depth.

	public SeededGrowth(int width, int height, int depth) {
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.n = width * height * depth;
	}

	/**
	 * Map a 3D voxel coordinate (x, y, z) to its position in the flat 1D arrays this class uses
	 * (mask, lab, seeds). Index order is x, then y, then z - i.e. slice by slice, row by row.
	 * The inverse is: x = i % width; y = (i / width) % height; z = i / (width * height).
	 *
	 * @return the flat array index, 0 .. n-1
	 */
	private int idx(int x, int y, int z) {
		return (z * height + y) * width + x;
	}

	// mask ------------------------------------------------------------------

	/** @return v > low for every voxel of the given channel stack. */
	public boolean [] mask(ImagePlus channelImp, double low) {
		// initialize one flag per voxel, false = background, n = number of voxels in the image
	    boolean [] m = new boolean [n];
	    
	    // iterate across all slices
	    for (int z = 0; z < depth; z++) {
	        ImageProcessor p = channelImp.getStack().getProcessor(z + 1);   // the 2D plane at depth z
	        int off = z * width * height;                           		// where this slice starts in the flat array
	        for (int i = 0; i < width * height; i++) {              		// iterating over every pixel of the slice
	            if (p.getf(i) > low) m[off + i] = true;             		// above the low threshold -> part of the object extent
	        }
	    }
	    return m;
	}

	// seed sources ------------------------------------------------------------------

	/**
	 * Seeds = connected components of (v > seedThr) inside a mask.
	 */
	public int [] seedsFromHighThreshold(ImagePlus channelImp, boolean [] mask, double seedThr) {
		boolean [] core = new boolean [n]; // voxels bright enough to be a seed
		for (int z = 0; z < depth; z++) {
			ImageProcessor p = channelImp.getStack().getProcessor(z + 1);
			int off = z * width * height;
			for (int i = 0; i < width * height; i++) {
				if (mask[off + i] && p.getf(i) > seedThr) core[off + i] = true;	// inside the extent AND above the higher seed threshold
			}
		}
		
		//In the following, each connected bright blob becomes one seed (its own id)
		return components(core);
	}
	
	/** 
	 * Seeds = one label per detected spot.
	 * The best source for PUNCTATE structures (basal bodies).
	 * */
	public int [] seedsFromSpots(ImagePlus channelImp, boolean [] mask,
			ciliaQ_Prep_jnh.spot_detection.SpotSettings st) {
		ciliaQ_Prep_jnh.spot_detection.SpotDetector.Result r =
				ciliaQ_Prep_jnh.spot_detection.SpotDetector.detect(channelImp, st);  // run DoG spot detection
		int [] seeds = new int [n];	// 0 = not a seed
		int next = 1;	 // next seed id to hand out
		for (int i = 0; i < r.spots.size(); i++) {	// every detected spot
			ciliaQ_Prep_jnh.spot_detection.SpotDetector.Spot s = r.spots.get(i);
			int id = idx(s.x, s.y, s.z);	// compute id: assign voxel position as index
			if (id >= 0 && id < n && mask[id] && seeds[id] == 0) {
				// accept only in bounds, inside the mask, not already a seed
				seeds[id] = next++;	 // plant a single-voxel seed with the fresh id
			}
				
		}
		return seeds;
	}
	
	/**
	 * Seeds = the rasterised Steger lines, one label per line.
	 */
	public int [] seedsFromRidges(ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.Result rr,
			boolean [] mask) {
		int [] seeds = new int [n];
		
		// Ridge labels arrive per slice as [slice][y*width+x]; copy them into the flat 3D order used in this class
		for (int z = 0; z < depth && z < rr.labels.length; z++) {
			int [] plane = rr.labels[z];	// the ridge label image for this slice
			int off = z * width * height;
			for (int i = 0; i < width * height && i < plane.length; i++) {
				if (plane[i] > 0 && mask[off + i]) {
					// Only allow ridge voxels within the mask
					// Keep the ridge's own line-id as the seed id
					seeds[off + i] = plane[i];
				}
			}
		}
		return seeds;
	}
	
	// growth ------------------------------------------------------------------

	/**
	 * Competitive breadth-first growth: every seed voxel is put in one queue and the frontier advances
	 * one shell at a time, so contested voxels go to the nearest seed (ties to whichever seed was
	 * enqueued first). Restricted to the mask = growth cannot jump across a background gap.
	 */
	public Result grow(boolean [] mask, int [] seeds, int minPieceSize) {
		return grow(mask, seeds, minPieceSize, true);
	}

	/**
	 * @param discardUnseeded  true reproduces CLASSIC HYSTERESIS: a low-mask component with no seed in
	 * it is dropped, exactly as hysteresis drops a low component that no high voxel touches. This is
	 * the default because it is what makes the tool "hysteresis plus splitting" rather than a new
	 * segmentation, and it is the setting under which the lossless check holds against classic
	 * hysteresis.
	 *
	 * false keeps those components and gives each its own label, so nothing is deleted.
	 * Either way the count is REPORTED, so discarding is never silent.
	 */
	public Result grow(boolean [] mask, int [] seeds, int minPieceSize, boolean discardUnseeded) {
		Result res = new Result();
		res.unseededDiscarded = discardUnseeded;
		
		/**
		 * Work on a copy: discarding unseeded components edits the mask, and a caller that runs several
		 * settings over one mask (as the guard sweep does) must not have it changed underneath.
		 */
		mask = mask.clone();
		
		int [] lab = new int [n];		// the label image being built, 0 = unlabelled
		int [] queue = new int [n];		// BFS frontier (flat voxel indices)
		int head = 0, tail = 0;			// queue read/write cursors

		/*
		 * Find the low-mask components that hold no seed BEFORE growing, because after growth every
		 * mask voxel carries a label and the distinction is gone.
		 */
		int [] comp0 = components(mask);	// label every connected mask blob
		
		// Determine highest blob id, which is the blob count
		int nComp0 = 0;
		for (int i = 0; i < n; i++) {
			if (comp0[i] > nComp0) {
				nComp0 = comp0[i];
			}
		}
		
		 // Check whether a blob has at least one seed
		boolean [] hasSeed = new boolean [nComp0 + 1];
		for (int i = 0; i < n; i++) {
			if (mask[i] && seeds[i] > 0) hasSeed[comp0[i]] = true;
		}
		
		int [] compSize = new int [nComp0 + 1];
		for (int i = 0; i < n; i++) if (comp0[i] > 0) compSize[comp0[i]]++;
		for (int c = 1; c <= nComp0; c++) {
			 // Tally the seedless blobs
			if (!hasSeed[c]) {
				res.nUnseeded++;
				res.unseededVoxels += compSize[c];
			}
		}
		if (discardUnseeded) {
			// classic-hysteresis behaviour: erase every blob that no seed touches
			for (int i = 0; i < n; i++) {
				if (mask[i] && comp0[i] > 0 && !hasSeed[comp0[i]]) mask[i] = false;
			}
		}
		
		int maxSeed = 0;	  				// track the largest seed id used
		for (int i = 0; i < n; i++) {
			if (seeds[i] > 0 && mask[i]) {	// a seed that survived inside the mask
				lab[i] = seeds[i];			// it starts already carrying its label
				queue[tail++] = i;			// and sits on the initial frontier
				if (seeds[i] > maxSeed) maxSeed = seeds[i];
			}
			if (mask[i]) res.foreground++;	// count mask voxels for the lossless check
		}
		res.nSeeds = countDistinct(lab);	 // how many distinct seed labels we started from

		while (head < tail) {					// until the frontier is exhausted
			int cur = queue[head++];			// next voxel to start from
			int x = cur % width;				// recover its 3D coordinates
			int y = (cur / width) % height;
			int z = cur / (width * height);
			int myLab = lab[cur];				// the label this voxel will hand to neighbours
			for (int dz = -1; dz <= 1; dz++) {
				int nz = z + dz;
				if (nz < 0 || nz >= depth) continue;
				for (int dy = -1; dy <= 1; dy++) {
					int ny = y + dy;
					if (ny < 0 || ny >= height) continue;
					for (int dx = -1; dx <= 1; dx++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						int nx = x + dx;
						if (nx < 0 || nx >= width) continue;
						int ni = idx(nx, ny, nz);
						if (!mask[ni] || lab[ni] != 0) continue;   // only claim mask voxels that are still unlabelled
						lab[ni] = myLab;                           // claim it for this seed (first to arrive wins)
						queue[tail++] = ni;                        // push it so it expands in turn
					}
				}
			}
		}

		/*
		 * Mask components with no seed inside them are still objects. Give each its own label, or the
		 * tool would silently delete whatever the seed detector missed - the opposite of lossless.
		 */
		
		int next = maxSeed + 1;									 // fresh ids start above the seed ids
		for (int i = 0; i < n; i++) {
			if (mask[i] && lab[i] == 0) {
				floodFill(mask, lab, i, next);	// fill the whole blob with one new id
				next++;
			}
		}

		res.labels = lab;
		applyMinimumPieceGuard(mask, lab, minPieceSize, res);	// undo over-eager splits (see below)
		res.nLabels = countDistinct(lab);
		return res;
	}

	/**
	 * Revert any mask component whose smallest piece is below the limit.
	 *
	 * It reverts the WHOLE component rather than dropping the small piece: dropping it would remove
	 * those voxels from the parent object and change its measured volume, which is a silent data
	 * change. Reverting only undoes a split the tool was not confident about.
	 *
	 * This is what makes the crossing case safe - see the class comment.
	 */
	private void applyMinimumPieceGuard(boolean [] mask, int [] lab, int minPieceSize, Result res) {
		int [] comp = components(mask);
		int nComp = 0;
		for (int i = 0; i < n; i++) if (comp[i] > nComp) nComp = comp[i];
		res.nComponents = nComp;
		if (nComp == 0) return;

		// for each blob, record which labels it contains and how many voxels each holds
	    ArrayList<java.util.HashMap<Integer,Integer>> perComp =
				new ArrayList<java.util.HashMap<Integer,Integer>>(nComp + 1);
		for (int c = 0; c <= nComp; c++) perComp.add(new java.util.HashMap<Integer,Integer>());
		for (int i = 0; i < n; i++) {
			if (comp[i] == 0 || lab[i] == 0) continue;
			java.util.HashMap<Integer,Integer> m = perComp.get(comp[i]);
			Integer k = Integer.valueOf(lab[i]);
			Integer had = m.get(k);
			
			// count how many voxels each label has, within this one connected blob.
			m.put(k, Integer.valueOf(had == null ? 1 : had.intValue() + 1));
		}

		boolean [] revert = new boolean [nComp + 1];	 // blobs whose split we will undo
		for (int c = 1; c <= nComp; c++) {
			java.util.HashMap<Integer,Integer> m = perComp.get(c);
			if (m.size() < 2) continue;					// only one label -> wasn't split, nothing to check
			int smallest = Integer.MAX_VALUE;			// size of the smallest piece in this blob
			for (Integer v : m.values()) if (v.intValue() < smallest) smallest = v.intValue();
			if (smallest < minPieceSize) {				// a piece too small to trust ->
				revert[c] = true;						// undo the whole split (keep the blob as one object)
				res.nReverted++;
			} else {
				res.nSplit++;							// a split we keep
			}
		}
		if (res.nReverted == 0) return;

		// one label per reverted component: reuse the smallest id already in it, so ids stay compact
		int [] keep = new int [nComp + 1];
		Arrays.fill(keep, 0);
		for (int i = 0; i < n; i++) {
			int c = comp[i];
			if (c == 0 || !revert[c] || lab[i] == 0) continue;
			if (keep[c] == 0 || lab[i] < keep[c]) keep[c] = lab[i];		// remember the smallest label id in the blob
		}
		for (int i = 0; i < n; i++) {
			int c = comp[i];
			if (c != 0 && revert[c] && lab[i] != 0) lab[i] = keep[c];	// overwrite with smallest id
		}
	}

	// ------------------------------------------------------------------ helpers

	/** 26-connected components of a boolean mask, labelled from 1. */
	public int [] components(boolean [] m) {
		int [] lab = new int [n];
		int next = 1;
		for (int i = 0; i < n; i++) {
			if (m[i] && lab[i] == 0) {						// first unlabelled voxel of a new blob
	            floodFill(m, lab, i, next);	// fill the whole blob with id `next`
				next++;
			}
		}
		return lab;
	}

	/** Iterative 26-connected fill
	 * 
	 *  */
	private void floodFill(boolean [] m, int [] lab, int start, int value) {
		int [] stack = new int [64];
		int sp = 0;
		stack[sp++] = start;
		lab[start] = value;
		while (sp > 0) {
			int cur = stack[--sp];
			int x = cur % width;
			int y = (cur / width) % height;
			int z = cur / (width * height);
			for (int dz = -1; dz <= 1; dz++) {
				int nz = z + dz;
				if (nz < 0 || nz >= depth) continue;
				for (int dy = -1; dy <= 1; dy++) {
					int ny = y + dy;
					if (ny < 0 || ny >= height) continue;
					for (int dx = -1; dx <= 1; dx++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						int nx = x + dx;
						if (nx < 0 || nx >= width) continue;
						int ni = idx(nx, ny, nz);
						if (!m[ni] || lab[ni] != 0) continue;
						lab[ni] = value;
						if (sp == stack.length) {
							int [] bigger = new int [stack.length * 2];
							System.arraycopy(stack, 0, bigger, 0, stack.length);
							stack = bigger;
						}
						stack[sp++] = ni;
					}
				}
			}
		}
	}

	private int countDistinct(int [] lab) {
		java.util.HashSet<Integer> s = new java.util.HashSet<Integer>();
		for (int i = 0; i < n; i++) if (lab[i] > 0) {
			s.add(Integer.valueOf(lab[i]));
		}
		return s.size();
	}

	/**
	 * Renumber to 1..k so the label ids are consecutive.
	 * Numbering must come from the voxels actually present, not from the seed ids.
	 */
	public int renumber(int [] lab) {
		java.util.HashMap<Integer,Integer> map = new java.util.HashMap<Integer,Integer>();
		int next = 1;
		for (int i = 0; i < n; i++) {
			if (lab[i] == 0) continue;
			Integer k = Integer.valueOf(lab[i]);
			Integer to = map.get(k);
			if (to == null) {
				to = Integer.valueOf(next++);
				map.put(k, to);
			}
			lab[i] = to.intValue();
		}
		return next - 1;
	}

	/** Highest label that fits without loss, mirroring RidgeSegmenter's rule. */
	public static int requiredBitDepth(int nLabels) {
		if (nLabels <= 255) return 8;
		if (nLabels <= 65535) return 16;
		return 32;
	}

	/** Write the labels into one channel of one frame of the output image. */
	public void writeLabels(int [] lab, ImagePlus writeImp, int channelWriteImp, int frame) {
		writeLabels(lab, width, height, depth, writeImp, channelWriteImp, frame);
	}

	/**
	 * Static form with explicit dimensions, so the caller can defer the write without keeping the
	 * engine instance alive. The Preparator needs this: promotion of the output image to a deeper bit
	 * depth must happen ONCE after every channel is segmented, so the labels are held and written
	 * afterwards - see finishPendingLabels in CiliaQPrepMain.
	 */
	public static void writeLabels(int [] lab, int width, int height, int depth, ImagePlus writeImp,
			int channelWriteImp, int frame) {
		for (int z = 0; z < depth; z++) {
			int stackIdx = writeImp.getStackIndex(channelWriteImp, z + 1, frame + 1) - 1;
			ImageProcessor p = writeImp.getStack().getProcessor(stackIdx + 1);
			int off = z * width * height;
			for (int i = 0; i < width * height; i++) p.setf(i, lab[off + i]);
		}
	}
}
