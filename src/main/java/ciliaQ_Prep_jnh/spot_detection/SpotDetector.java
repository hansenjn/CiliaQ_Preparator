package ciliaQ_Prep_jnh.spot_detection;

import java.util.ArrayList;
import java.util.Arrays;

import ij.ImagePlus;
import ij.plugin.GaussianBlur3D;
import ij.plugin.filter.MaximumFinder;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * CiliaQ Preparator - SPOT DETECTION (multi-scale DoG plus MaximumFinder)
 *
 * Notes:
 *  1. A NEW MaximumFinder MUST be constructed per call. It caches internal arrays sized to the last
 *     image, so reusing one across images of different sizes throws ArrayIndexOutOfBoundsException -
 *     which would crash any batch over mixed-size images.
 *  2. excludeOnEdges MUST be true. The DoG response genuinely peaks at the image border, so with the
 *     flag off a handful of false maxima survive any prominence setting.
 *
 * Added in v0.2.1.
 *
 * @author Jan N. Hansen
 */
public class SpotDetector {

	/** One detected spot, in pixel coordinates of the channel image. */
	public static class Spot {
		public final int x, y, z;
		public final float response;		// DoG response, used to pick the in-focus slice
		Spot(int x, int y, int z, float response) {
			this.x = x; this.y = y; this.z = z; this.response = response;
		}
	}

	public static class Result {
		public final ArrayList<Spot> spots;
		public final double minIntensityUsed;
		public final int rejectedByIntensity;
		public final int beforeZConsolidation;
		Result(ArrayList<Spot> s, double minUsed, int rejected, int beforeZ) {
			this.spots = s; this.minIntensityUsed = minUsed; this.rejectedByIntensity = rejected;
			this.beforeZConsolidation = beforeZ;
		}
	}

	/**
	 * Multi-scale DoG response, scale-normalised, maximised over scales.
	 * 2D blurs per slice would lose z structure, so GaussianBlur3D is used with sigmaZ scaled by the
	 * same factor -- the caller passes an already-extracted single-channel, single-timepoint stack.
	 */
	static float[][] dogResponse(ImagePlus imp, SpotSettings st) {
		final int W = imp.getWidth(), H = imp.getHeight(), D = imp.getStackSize();
		float[][] best = new float[D][W * H];
		for (int i = 0; i < D; i++) {
			Arrays.fill(best[i], Float.NEGATIVE_INFINITY);
		}

		for (double sigma : st.scales()) {
			ImagePlus blurSmall = imp.duplicate(), blurLarge = imp.duplicate();
			new ij.process.ImageConverter(blurSmall).convertToGray32();
			new ij.process.ImageConverter(blurLarge).convertToGray32();
			
			// Blur in z by the same physical distance as in xy (sigma * pixelWidth/pixelDepth). This self-shrinks
			// on coarsely-sampled z, and collapses to a 2D per-slice DoG (sigmaZ = 0) when z is too thin to help.
			double sigmaZ;
			if (D < 3) {
			    sigmaZ = 0;                         // 1–2 slices: no meaningful z to blur
			} else {
			    ij.measure.Calibration cal = imp.getCalibration();
			    double anis = (cal != null && cal.pixelDepth > 0 && cal.pixelWidth > 0)
			            ? cal.pixelWidth / cal.pixelDepth : 1.0;
			    sigmaZ = sigma * anis;              // self-shrinks when z is coarse
			    if (sigmaZ < 0.4) sigmaZ = 0;       // too small to be worth a 3D pass -> 2D
			}
			
			GaussianBlur3D.blur(blurSmall, sigma, sigma, sigmaZ);
			GaussianBlur3D.blur(blurLarge, sigma * st.scaleRatio, sigma * st.scaleRatio,
					sigmaZ * st.scaleRatio);
			double norm = sigma * sigma;			// scale normalisation, so scales are comparable
			for (int z = 0; z < D; z++) {
				ImageProcessor pSmall = blurSmall.getStack().getProcessor(z + 1);
				ImageProcessor pLarge = blurLarge.getStack().getProcessor(z + 1);
				for (int i = 0; i < W * H; i++) {
					float v = (float) ((pSmall.getf(i) - pLarge.getf(i)) * norm);
					if (v > best[z][i]) best[z][i] = v;
				}
			}
			blurSmall.changes = false; blurSmall.close();
			blurLarge.changes = false; blurLarge.close();
		}
		return best;
	}

	/** p10 of the non-zero values of the channel - the "auto" minimum spot intensity. */
	static double autoMinIntensity(ImagePlus imp) {
		final int W = imp.getWidth(), H = imp.getHeight(), D = imp.getStackSize();
		ArrayList<Float> nonZero = new ArrayList<Float>();
		for (int z = 0; z < D; z++) {
			ImageProcessor p = imp.getStack().getProcessor(z + 1);
			for (int i = 0; i < W * H; i++) {
				float v = p.getf(i);
				if (v > 0f) nonZero.add(Float.valueOf(v));
			}
		}
		if (nonZero.isEmpty()) return 0.0;
		float[] intensityList = new float[nonZero.size()];
		for (int i = 0; i < intensityList.length; i++) {
			intensityList[i] = nonZero.get(i).floatValue();
		}
		Arrays.sort(intensityList);
		return intensityList[(int) (intensityList.length * 0.10)];
	}

	/**
	 * Detect spots in a single-channel, single-timepoint stack.
	 * MaximumFinder is 2D, so it runs per slice on the 3D DoG response - the response itself carries the
	 * z information, which is why the blur is 3D even though the peak search is not.
	 */
	public static Result detect(ImagePlus channelImp, SpotSettings st) {
		final int W = channelImp.getWidth(), H = channelImp.getHeight(), D = channelImp.getStackSize();
		float[][] response = dogResponse(channelImp, st);

		double minInt = st.autoMinIntensity ? autoMinIntensity(channelImp) : st.minIntensity;

		ArrayList<Spot> spots = new ArrayList<Spot>();
		int rejected = 0;
		for (int z = 0; z < D; z++) {
			FloatProcessor fp = new FloatProcessor(W, H, response[z], null);
			// TRAP 1: a NEW MaximumFinder per call. Reusing one caches arrays sized for a previous image.
			MaximumFinder mf = new MaximumFinder();
			// TRAP 2: excludeOnEdges = true, or border maxima survive any prominence.
			java.awt.Polygon pts = mf.getMaxima(fp, st.sensitivity, true);
			if (pts == null) continue;
			ImageProcessor raw = channelImp.getStack().getProcessor(z + 1);
			for (int i = 0; i < pts.npoints; i++) {
				int x = pts.xpoints[i], y = pts.ypoints[i];
				if (x < 0 || x >= W || y < 0 || y >= H) continue;
				if (raw.getf(x, y) < minInt) { rejected++; continue; }
				spots.add(new Spot(x, y, z, response[z][y * W + x]));
			}
		}
		
		/*
		 * Z-CONSOLIDATION. MaximumFinder is 2D and runs per slice, so one 3D object produces one spot per
		 * slice it spans - inflating the count by the object's z extent. Merge spots that lie within one
		 * expected diameter in xy and belong to the same peak of the intensity profile through z, and keep
		 * the strongest DoG response per cluster, i.e. the object's in-focus slice (which also gives a
		 * meaningful z). A single slice is unaffected.
		 */
		int beforeConsolidation = spots.size();
		ArrayList<Spot> merged = consolidateAcrossSlices(spots, Math.max(2.0, st.diameter), channelImp, st.diameter / 4.0);
		return new Result(merged, minInt, rejected, beforeConsolidation);
	}

	/**
	 * Merge spots that belong to the same object across slices, keeping the strongest spot of each group.
	 *
	 * Two spots are merged when they lie within {@code radius} in xy AND their z intensity profiles climb to
	 * the same peak (within one plane). A faint tail or axial side lobe of a bright spot therefore joins it,
	 * while two spots stacked in z (e.g. basal bodies of cells above each other) stay separate.
	 * The peak search is the one ridge detection uses:
	 * {@link ciliaQ_Prep_jnh.ridge_detection.RidgeZ#climb(double[], int)}.
	 *
	 * @param spots        detected spots (any plane); not modified
	 * @param radius       xy merge radius in pixels
	 * @param channelImp   single-channel, single-frame stack the spots were found in
	 * @param profileSigma xy Gaussian blur applied to each plane before reading the z profile (0 = none)
	 * @return one spot per group, strongest first
	 */
	static ArrayList<Spot> consolidateAcrossSlices(ArrayList<Spot> spots, double radius, ImagePlus channelImp,
			double profileSigma) {
		ArrayList<Spot> keep = new ArrayList<Spot>();		// the result: one representative spot per group
		
		if (spots.isEmpty()) {
			// nothing to merge
			return keep;
		}
		
		// Sort strongest first, so each group is founded by (and represented by) its brightest member.
		Spot[] arr = spots.toArray(new Spot[0]);
		Arrays.sort(arr, new java.util.Comparator<Spot>() {
			public int compare(Spot a, Spot b) { return Float.compare(b.response, a.response); }
		});
		
		final int D = channelImp.getStackSize(), W = channelImp.getWidth();		// D = number of planes, W = row length for pixel indexing
		
		int[] peak = new int[arr.length];		// the profile peak each spot's plane belongs to (stays all 0 for a single plane, so the peak test below always passes)
		
		if (D > 1) {
			// Run only for multi-plane images
			
			// Smoothed copy of every plane. The blur averages a small xy neighbourhood, so the z profile read at one pixel is not dominated by single-pixel noise.
			float[][] smooth = new float[D][];
			for (int z = 0; z < D; z++) {
				FloatProcessor fp = (FloatProcessor) channelImp.getStack().getProcessor(z + 1).convertToFloat().duplicate();	// act on float copy; the input image stays untouched
				if (profileSigma > 0) {
					fp.blurGaussian(profileSigma);
				}
				smooth[z] = (float[]) fp.getPixels();							// flat pixel array, index = y * W + x
			}
			
			// For each spot: read the intensity at xy through all planes (create z profile), then climb from the spot's own plane to the peak that plane belongs to.
			double[] f = new double[D];
			for (int j = 0; j < arr.length; j++) {
				for (int z = 0; z < D; z++) f[z] = Math.max(1e-3, smooth[z][arr[j].y * W + arr[j].x]);
				peak[j] = ciliaQ_Prep_jnh.ridge_detection.RidgeZ.climb(f, arr[j].z);
			}
		}

		// Grouping, strongest first.
		boolean[] taken = new boolean[arr.length];
		double r2 = radius * radius;	// compare squared distances, avoids sqrt
		for (int i = 0; i < arr.length; i++) {
			if (taken[i]) {
				// already absorbed by a stronger spot
				continue;
			}
			keep.add(arr[i]);							// i founds a new group and represents it
			taken[i] = true;
			for (int j = i + 1; j < arr.length; j++) {		// only weaker spots can be absorbed
				if (taken[j]) continue;
				double dx = arr[i].x - arr[j].x, dy = arr[i].y - arr[j].y;
				// absorb j if it is close in xy AND climbs to the same z peak (within one plane)
				if (dx * dx + dy * dy <= r2 && Math.abs(peak[i] - peak[j]) <= 1) {
					taken[j] = true;
				}
			}
		}
		return keep;
	}

	/**
	 * Write one voxel per spot into the output image.
	 *
	 * Output is BINARY (one voxel per spot), not labelled regions, since a basal body needs no extent;
	 * only its centre is used downstream, from which every reported value is derived.
	 *
	 * CiliaQ must filter basal bodies by the basal-body minimum size (not the cilium minimum),
	 * and that minimum must allow single-voxel objects - otherwise these one-voxel spots are discarded.
	 */
	public static void writeSpots(Result res, ImagePlus writeImp, int channelWriteImp, int frame,
			double value) {
		for (int i = 0; i < res.spots.size(); i++) {
			Spot s = res.spots.get(i);
			int idx = writeImp.getStackIndex(channelWriteImp, s.z + 1, frame + 1) - 1;
			writeImp.getStack().setVoxel(s.x, s.y, idx, value);
		}
	}

	/** Zero the whole channel for one frame, before spots are written into it. */
	public static void clearChannel(ImagePlus writeImp, int channelWriteImp, int frame, int nSlices) {
		for (int s = 0; s < nSlices; s++) {
			int idx = writeImp.getStackIndex(channelWriteImp, s + 1, frame + 1) - 1;
			ImageProcessor p = writeImp.getStack().getProcessor(idx + 1);
			for (int i = 0; i < p.getWidth() * p.getHeight(); i++) p.setf(i, 0f);
		}
	}
	
	/** Utility class - static methods only; never instantiated. */
	private SpotDetector() { }
}
