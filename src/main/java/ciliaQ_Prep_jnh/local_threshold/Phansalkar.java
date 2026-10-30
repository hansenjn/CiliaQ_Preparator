package ciliaQ_Prep_jnh.local_threshold;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.plugin.filter.RankFilters;

/**
 * CiliaQ Preparator - Phansalkar local threshold, computed in-house.
 *
 *   t = mean * (1 + p*exp(-q*mean) + k*((sd/r) - 1))        foreground where value > t
 *   defaults k = 0.25, r = 0.5, p = 2, q = 10
 *
 * WHY IN-HOUSE RATHER THAN fiji.threshold.Auto_Local_Threshold:
 *  1. FIJI's implementation is 8-BIT ONLY - it casts the pixel array to byte[] and throws a
 *     ClassCastException on 16-bit data.
 *  2. Converting to 8-bit first is worse than lossy: convertToGray8 maps the slice min-max onto 0-255,
 *     so a narrow-range background collapses into one or two grey levels, the local mean there goes to
 *     almost zero, and the threshold with it - any pixel one grey level above its neighbourhood passes.
 *
 * This implementation reproduces Fiji's Auto_Local_Threshold (Phansalkar method) on 16-bit data.
 * Three details are required to match its output; each was wrong in an earlier attempt:
 *
 *  - CIRCULAR kernel. The local mean and variance are computed with RankFilters (MEAN and VARIANCE),
 *    which use a circular mask of the given radius. A square box filter (e.g. via integral images) gives
 *    different results. RankFilters ships with ImageJ, so this adds no dependency.
 *  - MIN-MAX normalisation. Before applying the formula the slice is stretched so its minimum maps to 0
 *    and its maximum to 1. Dividing by the maximum alone (without subtracting the minimum) only agrees
 *    when the data already starts at zero (see the second surface() overload).
 *  - NORMALISE BY THE IMAGE RANGE, not the data-type range. The range comes from the actual pixel values,
 *    not from the type maximum (e.g. 65535 for 16-bit). Using the type maximum would compress the image
 *    into part of [0,1] and distort the exp(-q*mean) term, which is only meaningful on a true [0,1] range.
 *
 * Note: Since this implementation uses min/max ranges, it is advised to combine it with a median filter.
 * @TODO add note in wiki
 *
 * Added in V0.2.1.
 *
 * @author Jan N. Hansen
 */
public class Phansalkar {

	public static final double K = 0.25, R = 0.5, P = 2.0, Q = 10.0;

	/**
	 * Threshold surface for one slice, in the ORIGINAL intensity units of that slice.
	 *
	 * Returned as a surface rather than applied in place, so the tool can treat it exactly like the
	 * local fraction surface and share one apply path.
	 *
	 * @param ip      the slice
	 * @param radius  kernel radius in pixels
	 * @param normMax the value to normalise by - the IMAGE maximum, see the class comment
	 * @return one threshold per pixel, in original units
	 */
	public static float [] surface(ImageProcessor ip, double radius, double normMax,
			double k, double r, double p, double q) {
		return surface(ip, radius, 0.0, normMax, k, r, p, q);
	}

	/**
	 * @param normMin the value mapped to 0, and @param normMax the value mapped to 1.
	 *
	 * Subtracting the minimum (min-max stretch, not divide-by-max) is what matches Fiji's
	 * Auto_Local_Threshold, which stretches to the full range before normalising. Dividing by the maximum
	 * alone agrees only when the data already starts at zero. exp(-q*mean) is only meaningful on a true
	 * [0,1] range, so the min-max form is also correct independently of matching the reference.
	 */
	public static float [] surface(ImageProcessor ip, double radius, double normMin, double normMax,
			double k, double r, double p, double q) {
		final int W = ip.getWidth(), H = ip.getHeight();
		final int n = W * H;
		double span = normMax - normMin;
		if (span <= 0.0) span = 1.0;

		// normalised copy, because exp(-q*mean) is only meaningful on [0,1]
		float [] norm = new float [n];
		for (int i = 0; i < n; i++) norm[i] = (float) ((ip.getf(i) - normMin) / span);

		FloatProcessor meanFp = new FloatProcessor(W, H, norm.clone(), null);
		FloatProcessor varFp = new FloatProcessor(W, H, norm.clone(), null);
		RankFilters rf = new RankFilters();
		rf.rank(meanFp, radius, RankFilters.MEAN);
		rf.rank(varFp, radius, RankFilters.VARIANCE);

		float [] out = new float [n];
		for (int i = 0; i < n; i++) {
			double mean = meanFp.getf(i);
			double var = varFp.getf(i);
			double sd = var > 0.0 ? Math.sqrt(var) : 0.0;
			double t = mean * (1.0 + p * Math.exp(-q * mean) + k * ((sd / r) - 1.0));
			out[i] = (float) (t * span + normMin);		// back to original units
		}
		return out;
	}

	public static float [] surface(ImageProcessor ip, double radius, double normMin, double normMax) {
		return surface(ip, radius, normMin, normMax, K, R, P, Q);
	}

	/** Convenience for data that starts at zero. */
	public static float [] surface(ImageProcessor ip, double radius, double normMax) {
		return surface(ip, radius, 0.0, normMax, K, R, P, Q);
	}

	/** @return { min, max } over the whole stack - the normalisation range. */
	public static double [] stackRange(ImagePlus imp) {
		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (int z = 0; z < imp.getStackSize(); z++) {
			ImageProcessor p = imp.getStack().getProcessor(z + 1);
			for (int i = 0; i < p.getWidth() * p.getHeight(); i++) {
				double v = p.getf(i);
				if (v < min) min = v;
				if (v > max) max = v;
			}
		}
		if (min > max) { min = 0.0; max = 1.0; }
		return new double [] { min, max };
	}

	/** @return the maximum value over the whole stack. */
	public static double stackMax(ImagePlus imp) {
		return stackRange(imp) [1];
	}

	/** Utility class - static methods only; never instantiated. */
	private Phansalkar() { }
}
