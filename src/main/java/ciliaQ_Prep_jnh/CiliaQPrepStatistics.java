package ciliaQ_Prep_jnh;
/** ===============================================================================
* CiliaQ_Preparator Version 0.2.1
*
* This program is free software; you can redistribute it and/or
* modify it under the terms of the GNU General Public License
* as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* Copyright (C) Jan Niklas Hansen
* Date: September 29, 2019 (This Version: November 11, 2026)
*
* For any questions please feel free to contact me (jan.hansen@uni-bonn.de).
* =============================================================================== */

import ij.ImagePlus;
import ij.process.ImageProcessor;

/**
 * New in V0.2.1: the image statistics of CiliaQ Preparator in one place, so every method computes them the same way.
 * 	- SignalRange: the signal range from tiles, for ridge detection and the ridge seeds;
 * 	- medianPercentilesMad: median, p99.9, MAD and p0.1 of a channel, for the ridge report and its noise check;
 * 	- positiveQuantile: a percentile of the values above 0, for the spot detector's automatic minimum intensity;
 * 	- ExactRanks: exact percentiles of 32-bit values without storing them (used by all of the above);
 * 	- rank: the value at a rank of a histogram of integer values.
 */
public class CiliaQPrepStatistics {

	/**
	 * New in V0.2.1: the signal range of a channel (p99.9 - median; median - p0.1 for dark lines), from which ridge
	 * detection and the ridge seeds derive their thresholds.
	 *
	 * Why tiles: measured on the WHOLE stack on example images, one bright artefact (dust, debris) moved the range
	 * by up to x16384, and in small crops of a cilium, the cilium itself filled the top 0.1 % and raised the thresholds
	 * until half of it was missed. So the range is the MEDIAN of up to 19 ranges:
	 * 	- the whole stack,
	 * 	- a 3x3 grid of tiles,
	 * 	- the same grid shifted by half a tile.
	 * Since an artefact moves only the tiles it lies in, this avoids distortion from artefacts.
	 *
	 * Tiles without a single positive voxel in the RAW channel (padding, Clear Outside, zero backgrounds) are left out:
	 * their range of 0 would pull the median to 0. This makes the range more robust across images with black areas from
	 * stitching non-rectangular regions or with large regions of 0 background. If the raw channel has no positive voxel
	 * at all, the whole stack alone gives the range (0).
	 *
	 * Memory and size: 8- and 16-bit channels are counted in one histogram per tile, with long bins, so even a zero
	 * background of more than 2^31 voxels cannot overflow a bin. 32-bit channels (e.g. after Divide by Background) go
	 * through ExactRanks (in this file): two passes, exact (the value sorting would give), about 30 MB whatever the image size,
	 * instead of storing and sorting every voxel (12 bytes per voxel, at most 2^31 voxels per tile).
	 */
	public static final class SignalRange {
	
		public static final int TILES = 19;
		
		/** The legend of tileList, for the reports of ridge detection and the ridge seeds (once per channel). */
		public static final String TILE_LEGEND = "whole = whole stack; g1-g9 = 3x3 grid, row by row; s1-s9 = the same grid"
				+ " shifted by half a tile; (...) = left out (no positive voxel in the raw channel)";
	
		/** The median of the kept tile ranges: the value the thresholds are derived from. */
		public final double range;
	
		/** Per tile: 0 = whole stack, 1-9 = grid, 10-18 = shifted grid (row by row); NaN where the tile does not exist,
		 *	it has no number as its range (every voxel NaN, Infinity - Infinity, or Infinity). */
		public final double [] tileRange = new double [TILES];
	
		/** Per tile: false where it was left out (no positive raw voxel) or does not exist. */
		public final boolean [] kept = new boolean [TILES];
	
		/** Per tile: whether it exists (small images have fewer tiles). */
		public final boolean [] exists = new boolean [TILES];
	
		/**
		 * @param channel		the preprocessed channel, one channel; all its planes and frames are measured
		 * @param raw			the image holding the unprocessed values of the same channel
		 * @param rawChannel	the channel in raw (1-based)
		 * @param firstFrame	the frame of raw that corresponds to frame 1 of channel (1-based)
		 * @param darkLine		median - p0.1 instead of p99.9 - median
		 */
		public SignalRange(ImagePlus channel, ImagePlus raw, int rawChannel, int firstFrame, boolean darkLine){
			int W = channel.getWidth(), H = channel.getHeight(), nZ = channel.getNSlices(), nT = channel.getNFrames();
	
			int nx = Math.min(3, W), ny = Math.min(3, H),		// small images: fewer tiles, shifted grid only if the shift is >= 1 px
					sx = W / 6, sy = H / 6;
	
			boolean shifted = sx > 0 || sy > 0;
			exists [0] = true;
			for(int i = 0; i < nx * ny; i++){
				exists [1 + i] = true;
				if(shifted)	exists [10 + i] = true;
			}
	
			//-------------------------------- Tile of every column and row --------------------------------
			/*	Computed once per image, so the voxel loop needs no division. The shifted grid starts sx, sy px later; the
			 *	pieces at opposite edges fall into one tile (the modulo), so every shifted tile has the size of a plain one. */
			int [] colPlain = new int [W], colShifted = new int [W], rowPlain = new int [H], rowShifted = new int [H];
			for(int x = 0; x < W; x++){
				colPlain [x] = x * nx / W;
				colShifted [x] = ((x + sx) % W) * nx / W;
			}
			for(int y = 0; y < H; y++){
				rowPlain [y] = 1 + (y * ny / H) * nx;
				rowShifted [y] = 10 + (((y + sy) % H) * ny / H) * nx;
			}
	
			// Voxels per tile: one plane counted, times planes and frames, since every tile runs through all of them
			long [] count = new long [TILES];	// long: the whole-stack tile of a large time-lapse exceeds the int range
			for(int y = 0; y < H; y++)	for(int x = 0; x < W; x++){
				count [0]++;
				count [rowPlain [y] + colPlain [x]]++;
				if(shifted)	count [rowShifted [y] + colShifted [x]]++;
			}
			for(int t = 0; t < TILES; t++)	count [t] *= (long) nZ * nT;
	
			//-------------------------------- Collect the values per tile --------------------------------
			double q = darkLine ? 0.001 : 0.999;	// percentiles as before: the value at rank (long)(n * q) of the sorted voxels
			boolean integer = channel.getBitDepth() != 32;
			long [][] hist = integer ? new long [TILES][] : null;	// long bins: a zero background can exceed the int range
			if(integer){
				for(int t = 0; t < TILES; t++)	if(exists [t])	hist [t] = new long [65536];
			}
			ExactRanks er = integer ? null : new ExactRanks(TILES, 2);	// 32-bit: rank 0 = median, rank 1 = p99.9 (p0.1)
			long [] nan = new long [TILES];	// Per tile, the NaN voxels of a 32-bit channel, left out of its ranks
			boolean [] positive = new boolean [TILES];
	
			ImageProcessor p, r;
			float v;
			int i, t1, t2 = 0, bin;
			for(int pass = 0; pass < (integer ? 1 : 2); pass++){	// 32-bit: pass 0 counts, pass 1 refines (see ExactRanks)
				if(pass == 1){
					for(int t = 0; t < TILES; t++){
						if(!exists [t])	continue;
						count [t] -= nan [t];	// the voxels that are numbers
						er.locate(t, 0, count [t] / 2);
						er.locate(t, 1, (long)(count [t] * q));
					}
				}
				for(int f = 0; f < nT; f++)	for(int z = 0; z < nZ; z++){
					p = channel.getStack().getProcessor(channel.getStackIndex(1, z+1, f+1));
					r = raw.getStack().getProcessor(raw.getStackIndex(rawChannel, z+1, firstFrame + f));
					for(int y = 0; y < H; y++){
						i = y * W;
						for(int x = 0; x < W; x++, i++){
							v = p.getf(i);
							t1 = rowPlain [y] + colPlain [x];
							if(shifted)	t2 = rowShifted [y] + colShifted [x];
							if(integer){
								bin = Math.max(0, Math.min(65535, (int) v));
								hist [0][bin]++;
								hist [t1][bin]++;
								if(shifted)	hist [t2][bin]++;
							}else if(pass == 0){
								if(v != v){	// New in V0.2.1: NaN (e.g. 0/0 after Divide by Background) is left out, counted per tile
									nan [0]++;
									nan [t1]++;
									if(shifted)	nan [t2]++;
								}else{
									er.count(0, v);
									er.count(t1, v);
									if(shifted)	er.count(t2, v);
								}
							}else{
								if(v == v){	// New in V0.2.1: NaN left out, as in pass 0
									er.refine(0, v);
									er.refine(t1, v);
									if(shifted)	er.refine(t2, v);
								}
								continue;	// the raw channel was checked in pass 0
							}
							if(r.getf(i) > 0){	// tile 0 (whole stack) is always kept, so only the grid tiles need the flag
								positive [t1] = true;
								if(shifted)	positive [t2] = true;
							}
						}
					}
				}
			}
	
			//-------------------------------- Range per tile and median --------------------------------
			double p50, pEdge;
			double [] keptRanges = new double [TILES];
			int nKept = 0;
			for (int t = 0; t < TILES; t++) {
				tileRange[t] = Double.NaN;
				if (!exists[t] || count[t] == 0)
					continue;
				if (integer) {
					p50 = rank(hist[t], count[t] / 2);
					pEdge = rank(hist[t], (long) (count[t] * q));
				} else {
					p50 = er.value(t, 0);
					pEdge = er.value(t, 1);
				}
				tileRange[t] = darkLine ? p50 - pEdge : pEdge - p50;
				kept[t] = (t == 0 || positive[t]) && Double.isFinite(tileRange[t]); // Only a tile with a finite range
				if (kept[t])
					keptRanges[nKept++] = tileRange[t];
			}
			java.util.Arrays.sort(keptRanges, 0, nKept);
			// No tile kept (every voxel NaN, or no tile with a finite range)
			range = nKept == 0 ? Double.NaN
					: nKept % 2 == 1 ? keptRanges[nKept / 2]
							: 0.5 * (keptRanges[nKept / 2 - 1] + keptRanges[nKept / 2]);
		}
	
		/** For the report: every tile's range, those left out in brackets, so padded or cleared areas can be found later. */
		public String tileList(java.text.DecimalFormat df){
			StringBuilder sb = new StringBuilder();
			for(int t = 0; t < TILES; t++){
				if (!exists[t])
					continue;
				if (sb.length() > 0)
					sb.append("; ");
				sb.append(t == 0 ? "whole " : (t < 10 ? "g" + t + " " : "s" + (t - 9) + " "));
				
				String value = Double.isNaN(tileRange[t]) ? "NaN" : df.format(tileRange[t]);
					// NaN: a 32-bit tile whose voxels are all NaN (or Infinity - Infinity)
				
				sb.append(kept[t] ? value : "(" + value + ")");
			}
			return sb.toString();
		}
	}

	/**
	 * Median, p99.9, MAD and p0.1 of a whole channel (every plane of every frame): for the ridge report,
	 * and the MAD for its noise check (RidgeSettings.rejectionReason). The same values as sorting the voxels (the value
	 * at rank (long)(n * q)), without storing them: sorting needed 12 bytes per voxel (a float and a double copy) and an
	 * int count, which overflows from 2^31 voxels on. A histogram for 8- and 16-bit (exact), ExactRanks for 32-bit
	 * (exact; the MAD in float precision, the precision of the voxels themselves). NaN voxels (e.g. 0/0 after Divide by
	 * Background) are left out: the ranks are those of the voxels that are numbers, and a channel with only NaN gives
	 * NaN. 8- and 16-bit channels have no NaN.
	 *
	 * @return { median, p99.9, MAD, p0.1 }
	 */
	public static double [] medianPercentilesMad(ImagePlus imp){
		final int W = imp.getWidth(), H = imp.getHeight(), D = imp.getStackSize();
		if (imp.getBitDepth() != 32) {
			final long n = (long) W * H * D;
			long[] hist = new long[65536];
			for (int z = 1; z <= D; z++) {
				ImageProcessor p = imp.getStack().getProcessor(z);
				for (int i = 0; i < W * H; i++)
					hist[Math.max(0, Math.min(65535, (int) p.getf(i)))]++;
			}
			double median = rank(hist, n / 2);
			long[] dev = new long[65536]; // the absolute deviations from the median: whole numbers, too
			for (int v = 0; v < 65536; v++)
				dev[(int) Math.abs(v - median)] += hist[v];
			return new double[] { median, rank(hist, (long) (n * 0.999)), rank(dev, n / 2),
					rank(hist, (long) (n * 0.001)) };
		}
		
		ExactRanks er = new ExactRanks(1, 3);
		long m = 0; // the voxels that are numbers
		for (int pass = 0; pass < 2; pass++) {
			if(pass == 1){
				if(m == 0) {
					// No voxel is a number (all NaN): nothing to locate; NaN is returned below
					break;
				}				
				er.locate(0, 0, m / 2);
				er.locate(0, 1, (long) (m * 0.999));
				er.locate(0, 2, (long) (m * 0.001));
			}
			for (int z = 1; z <= D; z++) {
				ImageProcessor p = imp.getStack().getProcessor(z);
				for (int i = 0; i < W * H; i++) {
					float v = p.getf(i);
					if (v != v)
						continue; // NaN
					if (pass == 0) {
						er.count(0, v);
						m++;
					} else {
						er.refine(0, v);
					}
				}
			}
		}
		
		if (m == 0)
			return new double[] { Double.NaN, Double.NaN, Double.NaN, Double.NaN };
		
		double median = er.value(0, 0);
		ExactRanks dev = new ExactRanks(1, 1); // the absolute deviations from the median
		for (int pass = 0; pass < 2; pass++) {
			if (pass == 1)
				dev.locate(0, 0, m / 2);
			for (int z = 1; z <= D; z++) {
				ImageProcessor p = imp.getStack().getProcessor(z);
				for (int i = 0; i < W * H; i++) {
					float v = p.getf(i);
					if (v != v)
						continue; // NaN
					float d = (float) Math.abs(v - median);
					if (pass == 0)
						dev.count(0, d);
					else
						dev.refine(0, d);
				}
			}
		}
		
		return new double[] { median, er.value(0, 1), dev.value(0, 0), er.value(0, 2) };
	}

	/**
	 * The value at the fraction q of the sorted values above 0 of a channel (rank (long)(m * q) of the m
	 * values above 0; 0 if there are none), as sorting them gives it, without the list of every such value: the spot
	 * detector's automatic minimum intensity boxed each one (about 16 bytes per voxel, 4 GB for a large stack).
	 * A histogram for 8- and 16-bit, ExactRanks for 32-bit; NaN is not above 0, so it is left out, as before.
	 */
	public static double positiveQuantile(ImagePlus imp, double q){
		final int W = imp.getWidth(), H = imp.getHeight(), D = imp.getStackSize();
		final boolean integer = imp.getBitDepth() != 32;
		long[] hist = integer ? new long[65536] : null;
		ExactRanks er = integer ? null : new ExactRanks(1, 1);
		long m = 0;
		for (int pass = 0; pass < (integer ? 1 : 2); pass++) {
			if (pass == 1) {
				if(m == 0) {
					// No value above 0: nothing to locate; 0 is returned below
					break;
				}
				er.locate(0, 0, (long) (m * q));
			}				
			for (int z = 1; z <= D; z++) {
				ImageProcessor p = imp.getStack().getProcessor(z);
				for (int i = 0; i < W * H; i++) {
					float v = p.getf(i);
					if (!(v > 0f))
						continue;
					if (integer)
						hist[Math.min(65535, (int) v)]++;
					else if (pass == 0)
						er.count(0, v);
					else
						er.refine(0, v);
					if (pass == 0)
						m++;
				}
			}
		}
		if (m == 0)
			return 0.0;
		return integer ? rank(hist, (long) (m * q)) : er.value(0, 0);
	}

	/** The value at 0-based rank k of a histogram of integer values. */
	static double rank(long [] hist, long k){
		long acc = 0;
		for(int v = 0; v < hist.length; v++){
			acc += hist [v];
			if(acc > k)	return v;
		}
		return hist.length - 1;
	}

	/**
	 * Exact ranks (percentiles) of float values, without storing or sorting the values. Used for 32-bit channels;
	 * a class (inside this file) because the counts must be kept between the two passes.
	 *
	 * Why: the 32-bit channels (e.g. after Divide by Background) cannot use a histogram of integer values. Storing and
	 * sorting every voxel needs 4 bytes per voxel and value list (12 bytes per voxel for the 19 tiles of SignalRange)
	 * and fails beyond 2^31 voxels, the size of one Java array.
	 *
	 * How: every float becomes an int "sort key" whose order is exactly the order of java.util.Arrays.sort (-0 before
	 * +0, every NaN after +Infinity). Two passes over the values then give the value at any rank exactly:
	 * 	- pass 1 counts the keys by their upper 16 bits (count);
	 * 	- locate finds, for each wanted rank, the bucket it falls in and its rank inside that bucket;
	 * 	- pass 2 counts the lower 16 bits, only for values in those buckets (refine);
	 * 	- value reads the exact value. It is the value Arrays.sort would put at that rank, not an approximation.
	 * Memory: (groups + groups x ranks) x 65536 longs, whatever the number of values (19 tiles x 2 ranks: 30 MB).
	 *
	 * Usage: count(...) for every value; locate(...) for every wanted rank; refine(...) for every value again;
	 * value(...) for every wanted rank.
	 */
	static final class ExactRanks {
		private final int ranks;
		private final long [][] hi;			// per group: counts of the upper 16 bits of the sort key
		private final long [][] lo;			// per group and rank: counts of the lower 16 bits, inside that rank's bucket
		private final int [][] bucket;		// per group and rank: the bucket (upper 16 bits) of that rank, -1 if none
		private final long [][] inBucket;	// per group and rank: the rank inside that bucket

		/**
		 * @param groups	the number of independent value groups (e.g. the tiles of SignalRange)
		 * @param ranks		the number of ranks wanted per group
		 */
		ExactRanks(int groups, int ranks){
			this.ranks = ranks;
			hi = new long [groups][65536];
			lo = new long [groups * ranks][];	// allocated in locate, only for the groups that have values
			bucket = new int [groups][ranks];
			inBucket = new long [groups][ranks];
			for(int g = 0; g < groups; g++)	java.util.Arrays.fill(bucket [g], -1);
		}

		/** Pass 1: one value of group g. */
		void count(int g, float v){
			hi [g][high(sortKey(v))]++;
		}

		/** Between the passes: rank number j of group g is the value at 0-based rank k of its sorted values. */
		void locate(int g, int j, long k){
			long acc = 0;
			for(int b = 0; b < 65536; b++){
				if(acc + hi [g][b] > k){
					bucket [g][j] = b;
					inBucket [g][j] = k - acc;
					lo [g * ranks + j] = new long [65536];
					return;
				}
				acc += hi [g][b];
			}
		}

		/** Pass 2: the same value of group g again. */
		void refine(int g, float v){
			int key = sortKey(v), h = high(key);
			for(int j = 0; j < ranks; j++){
				if(h == bucket [g][j])	lo [g * ranks + j][key & 0xffff]++;
			}
		}

		/** After pass 2: the value of rank number j of group g (NaN if the group had fewer values than that rank). */
		float value(int g, int j){
			if(bucket [g][j] < 0)	return Float.NaN;
			long [] l = lo [g * ranks + j];
			long acc = 0;
			for(int b = 0; b < 65536; b++){
				acc += l [b];
				if(acc > inBucket [g][j])	return fromSortKey(((bucket [g][j] << 16) | b) ^ 0x80000000);
			}
			return Float.NaN;
		}

		/** A float as an int whose order is the order of Arrays.sort. floatToIntBits, not the raw variant: all NaN
		 *  become one value, above +Infinity, which is where Arrays.sort puts them. */
		static int sortKey(float v){
			int b = Float.floatToIntBits(v);
			return b ^ ((b >> 31) & 0x7fffffff);
		}

		static float fromSortKey(int k){
			return Float.intBitsToFloat(k ^ ((k >> 31) & 0x7fffffff));
		}

		/** The upper 16 bits of a key, 0-65535 in key order (the sign bit flipped, so negative keys come first). */
		private static int high(int key){
			return (key ^ 0x80000000) >>> 16;
		}
	}
	
	/**
	 * Numbers for settings files and recorded macros, written so that reading them back gives the same value.
	 * Values that are exact with the usual number of decimals are written as before, so the files of normal runs do
	 * not change.
	 */
	public static final class Numbers {

		private Numbers(){}

		/**
		 * @return v with at least minDecimals decimals, and more where needed so that Double.parseDouble gives v back
		 * (e.g. a contrast sensitivity of 0.0908 is no longer written as 0.091); always a dot, never an exponent.
		 */
		public static String exact(double v, int minDecimals){
			if(Double.isNaN(v) || Double.isInfinite(v))	return Double.toString(v);
			java.math.BigDecimal b = new java.math.BigDecimal(Double.toString(v));	// the shortest text that gives v back
			if(b.scale() < minDecimals)	b = b.setScale(minDecimals);	// pad with zeros, never round
			return b.toPlainString();
		}
	}
}
