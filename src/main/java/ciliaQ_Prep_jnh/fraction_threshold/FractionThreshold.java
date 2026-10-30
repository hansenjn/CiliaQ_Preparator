package ciliaQ_Prep_jnh.fraction_threshold;

import java.util.Arrays;

import ij.ImagePlus;
import ij.process.ImageProcessor;

/**
 * CiliaQ Preparator - the two FRACTION-OF-INTENSITY-RANGE threshold methods.
 *
 * Both are SIGNAL-anchored: This method assumes that the threshold sits at a fraction of the difference
 * between the background level up and the signal level, rather than a number of noise standard deviations
 * above the background (classical threshold algorithms). This is applicable particularly for "noise-free"
 * detectors where both, median and MAD, are 0. The same applies for background-subtracted data. In such 0-
 * background images the noise-determined methods often degenerate to "any non-zero voxel".
 *
 * We define two formulas to determine the threshold as a fixed fraction:
 *  1) global:  thr    = bg + f_g * (signal - bg)
 *  2) local:   thr(x) = max(global, bg_tile + f_l * (signal_tile - bg_tile))
 *
 * The local threshold is floored at the global one since without that floor a tile containing only
 * background will consider bg == signal, so its threshold reveals noise; the floor makes an empty tile
 * behave exactly as the global method would.
 *
 * Like for other methods in CiliaQ Preparator, statistics are determined on the processed image,
 * after any median / pre-blur / subtract-bg / divide-bg / post-blur, and from whatever the user's 
 * Stack processing choice produced - whole stack, maximum projection or a single slice.
 * This method class does not need to know the raw image and processing method since this method
 * works directly on the processed image.
 *
 * The method is applied in 2D even for a stack. For the maximum-projection option, global statistics
 * and the tile grid are calculated on the projection and then applied to every slice.
 * 
 * Added in v0.2.1.
 * 
 * @author Jan N. Hansen
 */
public class FractionThreshold {

	/** Result of one threshold computation. */
	public static class Result {
		public final double global;
		public final float [] surface;		// null for the global method; else width*height of source
		public final int width, height;
		public final double surfaceMin, surfaceMax;
		Result(double global, float [] surface, int w, int h, double mn, double mx) {
			this.global = global; this.surface = surface; this.width = w; this.height = h;
			this.surfaceMin = mn; this.surfaceMax = mx;
		}
		public boolean isLocal() { return surface != null; }
	}

	/** @return the value at the given percentile of a sorted array; p in 0..100. */
	static double percentile(float [] sorted, double p) {
		if (sorted.length == 0) return 0.0;
		int i = (int) Math.floor(p / 100.0 * (sorted.length - 1) + 0.5);
		if (i < 0) i = 0;
		if (i >= sorted.length) i = sorted.length - 1;
		return sorted[i];
	}

	private static float [] allPixelsSorted(ImagePlus imp) {
		final int W = imp.getWidth(), H = imp.getHeight(), D = imp.getStackSize();
		float [] all = new float [W * H * D];
		int k = 0;
		for (int z = 0; z < D; z++) {
			ImageProcessor p = imp.getStack().getProcessor(z + 1);
			for (int i = 0; i < W * H; i++) {
				all[k++] = p.getf(i);
			}
		}
		Arrays.sort(all);
		return all;
	}

	/** The global threshold, on whatever image the stack-processing choice produced. */
	public static double global(ImagePlus sourceImp, FractionSettings st) {
		float [] all = allPixelsSorted(sourceImp);
		double background = percentile(all, st.bgPercentile);
		double signal = percentile(all, st.signalPercentileGlobal);
		return background + st.fGlobal * Math.max(0.0, signal - background);
	}

	/**
	 * The local threshold surface, floored at the global threshold.
	 *
	 * Tile statistics pool every slice of the source in that tile column, so a tile's estimate uses all
	 * the data it has. Tiles at the right and bottom edges are whatever is left over rather than being
	 * padded, because padding with zeros would drag their background estimate down.
	 *
	 * The surface is then bilinearly interpolated between tile CENTRES. Blocky per-tile thresholds
	 * produce visible tile seams in the mask, and a seam is an artefact that looks like structure.
	 */
	public static Result local(ImagePlus sourceImp, FractionSettings st) {
		final int W = sourceImp.getWidth(), H = sourceImp.getHeight(), D = sourceImp.getStackSize();
		double globalThr = global(sourceImp, st);

		int tileSidePx = Math.max(8, st.tileSize);	// tile edge length in pixels; never smaller than 8 px, or tiles hold too few pixels
		int nTileCols = Math.max(1, (int) Math.ceil(W / (double) tileSidePx));	// number of tile columns
		int nTileRows = Math.max(1, (int) Math.ceil(H / (double) tileSidePx));	// number of tile rows
				
		double [][] tileThreshold = new double [nTileRows][nTileCols];	// per-tile threshold grid [row][col], one threshold per tile

		// --- stage 1: a threshold for each tile ------------------------------------------------
		for (int ty = 0; ty < nTileRows; ty++) {
			int tileY0 = ty * tileSidePx, tileY1 = Math.min(H, tileY0 + tileSidePx);		// this tile's y-extent (clipped at the edge)
			for (int tx = 0; tx < nTileCols; tx++) {
				int tileX0 = tx * tileSidePx, tileX1 = Math.min(W, tileX0 + tileSidePx);	// this tile's x-extent (clipped at the edge)
				int tilePixelCount = (tileX1 - tileX0) * (tileY1 - tileY0) * D;						// pixels in this tile across all slices
				float [] tilePixels = new float [tilePixelCount];
				int k = 0;
				for (int z = 0; z < D; z++) {							// pool pixels across all slices for a tile
					ImageProcessor p = sourceImp.getStack().getProcessor(z + 1);
					for (int y = tileY0; y < tileY1; y++)
						for (int x = tileX0; x < tileX1; x++) tilePixels[k++] = p.getf(x, y);
				}
				Arrays.sort(tilePixels);										// sort values
				double bg = percentile(tilePixels, st.bgPercentile);	// get background
				double signal = percentile(tilePixels, st.signalPercentileLocal);
				double t = bg + st.fLocal * Math.max(0.0, signal - bg); // calculate threshold
				tileThreshold[ty][tx] = Math.max(globalThr, t);			// save threshold that is bigger (global or local)
			}
		}
		
		// --- stage 2: bilinearly interpolate the tile thresholds into a per-pixel surface -------
		float [] surface = new float [W * H];
		double surfaceMin = Double.MAX_VALUE, surfaceMax = -Double.MAX_VALUE;	// track surface range for the report
		for (int y = 0; y < H; y++) {
			/* 
			 * Position in tile-centre coordinates:
			 * y expressed in units of "tiles from the first tile centre"; the -tileSidePx/2 shifts the origin
			 * to the centre of tile 0, so interpolation happens between centres, not tile corners
			 */
			double rowInTiles = (y - tileSidePx / 2.0) / tileSidePx;	//pixel y position in tile units, from first row's centre
			int rowAbove = (int) Math.floor(rowInTiles);	// tile row whose centre is just above the pixel
			double weightDown = rowInTiles - rowAbove;	//vertical blend weight, 0 = on rowAbove, 1 = on rowBelow
			if (rowAbove < 0) { rowAbove = 0; weightDown = 0.0; }					// above the first centre: clamp to tile 0
			if (rowAbove >= nTileRows - 1) { rowAbove = Math.max(0, nTileRows - 2); weightDown = (nTileRows == 1) ? 0.0 : 1.0; }	 // below last centre: clamp to last tile
			int rowBelow = Math.min(nTileRows - 1, rowAbove + 1);			// tile row whose centre is just below the pixel
			for (int x = 0; x < W; x++) {
				double colInTiles = (x - tileSidePx / 2.0) / tileSidePx;	//pixel x position in tile units, from first col's centre
				int colLeft = (int) Math.floor(colInTiles);	//tile column whose centre is just left of the pixel
				double weightRight = colInTiles - colLeft;	//horizontal blend weight, 0 = on colLeft, 1 = on colRight
				if (colLeft < 0) { colLeft = 0; weightRight = 0.0; }
				if (colLeft >= nTileCols - 1) { colLeft = Math.max(0, nTileCols - 2); weightRight = (nTileCols == 1) ? 0.0 : 1.0; }
				int colRight = Math.min(nTileCols - 1, colLeft + 1);	//tile column whose centre is just right of the pixel
				
				double top = tileThreshold[rowAbove][colLeft] * (1 - weightRight) + tileThreshold[rowAbove][colRight] * weightRight;
					//top: threshold interpolated along x on the upper row
				double bot = tileThreshold[rowBelow][colLeft] * (1 - weightRight) + tileThreshold[rowBelow][colRight] * weightRight;
					//bot: threshold interpolated along x on the lower row
				
				//pixelThreshold: final bilinearly interpolated threshold for this pixel
				double pixelThreshold = top * (1 - weightDown) + bot * weightDown;
				
				// Save computed threshold:
				surface[y * W + x] = (float) pixelThreshold;
				
				// Update min / max for record keeping, if needed:
				if (pixelThreshold < surfaceMin) surfaceMin = pixelThreshold;
				if (pixelThreshold > surfaceMax) surfaceMax = pixelThreshold;
			}
		}
		return new Result(globalThr, surface, W, H, surfaceMin, surfaceMax);
	}

	/** Convenience: the Result for whichever of the two methods was chosen. */
	public static Result compute(ImagePlus sourceImp, FractionSettings st, boolean localMethod) {
		if (localMethod) return local(sourceImp, st);
		double g = global(sourceImp, st);
		return new Result(g, null, sourceImp.getWidth(), sourceImp.getHeight(), g, g);
	}
}
