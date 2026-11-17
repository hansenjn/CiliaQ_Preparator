package ciliaQ_Prep_jnh.spot_detection;

import ij.IJ;
import ij.gui.GenericDialog;

import ciliaQ_Prep_jnh.CiliaQPrepMain;
import ciliaQ_Prep_jnh.CiliaQPrepStatistics.Numbers;

/**
 * CiliaQ Preparator - settings for SPOT DETECTION (multi-scale DoG plus MaximumFinder).
 *
 * SPOT DETECTION implemented for punctate structures (basal bodies or any other sub-resolution spot).
 * 
 * This class is a per-channel settings object built either by its own dialog or by initDefault()
 * when loading from a settings file.
 *
 * DESIGN DECISIONS:
 *
 *  - PARAMETERISE BY DIAMETER, NOT SIGMA. Users know object size, not Gaussian sigma.
 *    sigma = diameter / (2 * 2.35) is the FWHM relation.
 *  - MULTI-SCALE, not single scale. Searching a small range of scales detects more true spots at the
 *    same sensitivity than a single large sigma.
 *  - THE SENSITIVITY IS A MINIMUM SPOT CONTRAST IN PIXEL INTENSITY ("GREY LEVEL"): how far a spot must stand above its immediate
 *    surroundings, whatever the background level. At each scale, dogResponse subtracts a Gaussian blur of sigma r*g from
 *    one of sigma g, where g is the scale's sigma and r the scale ratio, and multiplies the difference by g^2 (scale
 *    normalisation). Since the scales follow the expected diameter, a spot of fixed contrast gives a response that grows
 *    with the square of its diameter (unitResponse). The maximum search therefore uses contrast x unitResponse as its
 *    prominence. Since this cancels the growth, the same value means the same contrast at every diameter. So a user can
 *    read the contrast off the preprocessed image, e.g. with a line profile across a spot, and change the diameter
 *    without retuning it. The value is absolute: a threshold taken from the image's bright part (a percentile, the maximum)
 *    always admits the brightest voxels, which on an image without spots are noise; an absolute contrast can find none. 
 *    Since intensity depend on the bit depth, contrastNote checks the value against the channel, e.g. a 16-bit value
 *    used on 8-bit data. It is an IN-PLANE contrast: unitResponse assumes a 2D spot, while on stacks of 3 or more planes
 *    the filter also blurs in z. Since that lowers the peak of a spot of finite z-extent, a spot that is thin in z needs
 *    somewhat more contrast than the value to be found.
 *  - ONE dropdown entry, not two. DoG at ratio 1.6 IS the LoG approximation - the same code path - so
 *    offering both would be a choice with no difference. The ratio is exposed as an advanced field.
 *
 * Added in v0.2.1.
 *
 * @author Jan N. Hansen
 */
public class SpotSettings {

	static final String pluginName = CiliaQPrepMain.PLUGIN_NAME;
	static final String pluginVersion = CiliaQPrepMain.PLUGIN_VERSION;


	/**	The default minimum spot contrast: a starting value for 16-bit fluorescence images, chosen on test images;
	 * 	Needs to be adjusted to the contrast of the spots, e.g. measured with a line profile. */
	public static final double DEFAULT_CONTRAST = 600.0;

	/**	Default chosen based on measurements in 16-bit images. Needs adaptation to the images and microscope, too. */
	public static final double LOW_CONTRAST_16BIT = 100.0;
	
	/** Simple section. */
	public double diameter = 5.0;			// pixels, expected object diameter
	public double variation = 1.0;			// pixels, spread of diameters -> multi-scale range
	public double sensitivity = DEFAULT_CONTRAST;	// the MINIMUM SPOT CONTRAST in intensity



	/** Advanced section. */
	public boolean autoMinIntensity = true;
	public double minIntensity = 0.0;		// used only when autoMinIntensity is false
	public double scaleRatio = 1.6;			// 1.6 approximates LoG

	/** FWHM <-> sigma. */
	public static final double FWHM = 2.35;

	private SpotSettings() {
		super();
	}

	public static SpotSettings initDefault() {
		return new SpotSettings();
	}

	/** Central sigma for the expected diameter. */
	public double centreSigma() {
		return diameter / (2.0 * FWHM);
	}

	/**
	 * @return the DoG response at the centre of a spot of contrast 1 with the expected diameter, computed as
	 * SpotDetector.dogResponse computes it. A spot of contrast c gives c times this value.
	 * 
	 * The spot is a 2D Gaussian of sigma s, the centre sigma. Blurred by a Gaussian of sigma g, its peak drops to
	 * s^2 / (s^2 + g^2). At a scale g from scales(), dogResponse subtracts the blur of sigma r*g (r: the scale ratio)
	 * from the blur of sigma g and multiplies by g^2. The response at that scale is therefore
	 * g^2 * (s^2 / (s^2 + g^2) - s^2 / (s^2 + (r g)^2)), and this method returns its maximum over the scales.
	 * Since the scales follow the expected diameter, the value grows with the square of the diameter. Dividing by it
	 * (prominence) therefore turns the minimum spot contrast into a threshold that performs similarly at every diameter.
	 */
	public double unitResponse() {
		double s2 = centreSigma() * centreSigma(), best = 0.0;
		for (double g : scales()) {
			double g2 = g * g, rg2 = scaleRatio * scaleRatio * g2;
			best = Math.max(best, g2 * (s2 / (s2 + g2) - s2 / (s2 + rg2)));
		}
		return best;
	}

	/**
	 * @return the prominence of the maximum search: the DoG response of a spot with the minimum contrast, i.e. the
	 * minimum contrast times unitResponse.
	 * 
	 * A maximum of the DoG image counts as a spot only if it stands out by at least this
	 * much, so a spot is found when its contrast reaches the minimum spot contrast, whatever the diameter.
	 */
	public double prominence() {
		return sensitivity * unitResponse();
	}

	/**
	 * The minimum spot contrast that gives this prominence at these settings (diameter, variation, scale ratio): the
	 * inverse of prominence. If unitResponse is 0 (no scales), the prominence is returned unchanged.
	 *
	 * @param prominence	a prominence of the maximum search, in DoG response units
	 * @return the matching minimum spot contrast
	 */
	public double contrastForProminence(double prominence) {
		double u = unitResponse();
		return u > 0.0 ? prominence / u : prominence;
	}

	/**
	 * A one-line note when the minimum spot contrast does not fit the channel, otherwise null. Since the contrast is in
	 * the channel's units, a value that fits one image can be impossible or too permissive on another. The notes:
	 * 	- not below the channel's range (maximum - minimum after preprocessing): no spot can stand out that much, so none
	 * 	  is found, e.g. a 16-bit value used on 8-bit data, or a pixel-intensity value after Divide by Background;
	 * 	- below LOW_CONTRAST_16BIT on 16-bit data whose maximum is above 4095: background noise may pass as spots. Since
	 * 	  12-bit data saved as 16-bit never exceeds 4095, this note only applies to data using more than 12 bits.
	 *
	 * @param contrast	the minimum spot contrast
	 * @param bitDepth	the image's bit depth before preprocessing (8, 16 or 32)
	 * @param min		the channel's minimum after preprocessing
	 * @param max		the channel's maximum after preprocessing
	 * @param float32	true if the channel is 32-bit after preprocessing, e.g. ratios around 1 after Divide by
	 * 					Background; then only the range note applies, since 16-bit noise levels do not apply
	 * @return the note, or null if the contrast fits
	 */
	public static String contrastNote(double contrast, int bitDepth, double min, double max, boolean float32) {
		if (!(max > min) || contrast >= max - min)
			return "the minimum spot contrast (" + IJ.d2s(contrast, 1) + ") is not below the channel's range"
					+ " after preprocessing (maximum - minimum = " + IJ.d2s(max - min, 3) + "), so no spot can stand out"
					+ " that much. "
					+ (float32 ? "The channel is 32-bit after preprocessing, so the contrast is in its units"
							+ " (e.g. ratios around 1 after Divide by Background)."
							: "The contrast is in pixel intensities and depends on the bit depth (8-bit: 0-255, 16-bit: 0-65535).");
		if (!float32 && bitDepth == 16 && max > 4095.0 && contrast < LOW_CONTRAST_16BIT)
			return "the minimum spot contrast (" + IJ.d2s(contrast, 1) + " pixel intensities) seems low for 16-bit data"
					+ " (maximum " + IJ.d2s(max, 0) + "): background noise may pass as spots.";
		return null;
	}
	
	/**
	 * The scales to search: centre sigma plus/minus twice the sigma-equivalent of the diameter
	 * variation, clamped so no scale is degenerate.
	 */
	public double[] scales() {
		double centre = centreSigma();						// sigma for the expected diameter
		double half = 2.0 * (variation / (2.0 * FWHM));		// half the scale range, from the diameter variation
		double lo = Math.max(0.4, centre - half);		// smallest sigma, floored so it is never degenerate
		double hi = Math.max(lo, centre + half);		// largest sigma
		if (hi - lo < 1e-6) return new double[] { centre };	// range collapsed -> a single scale
		return new double[] { lo, 0.5 * (lo + hi), hi };	// three scales: low, mid, high
	}
	
	// ------------------------------------------------------------------ dialog

	/** The wiki page of spot detection, the target of the dialog's Help button. */
	public static final String HELP_URL = "https://github.com/hansenjn/CiliaQ/wiki/Spot-detection-in-CiliaQ-Preparator";
	
	public static SpotSettings initByGD(String task) throws Exception {
		SpotSettings inst = new SpotSettings();

		GenericDialog gd = new GenericDialog(pluginName + " - spot detection settings");
		gd.setInsets(0, 0, 0);
		gd.addMessage("Spot detection (multi-scale DoG) - version " + pluginVersion,
				CiliaQPrepMain.HEADING_FONT);
		gd.setInsets(10, 0, 0);
		gd.addMessage("Settings for " + task, CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addMessage("Intended for PUNCTATE structures (basal bodies). For filaments use ridge detection.",
				CiliaQPrepMain.INSTRUCTIONS_FONT);

		gd.setInsets(10, 0, 0);
		gd.addNumericField("Expected object diameter (pixels)", inst.diameter, 2);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Diameter variation (pixels)", inst.variation, 2);

		gd.setInsets(0, 0, 0);
		gd.addMessage("Set diameter for your pixel size, then adjust the minimum spot contrast (see Help).",
				CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Minimum spot contrast", inst.sensitivity, 3);
		gd.setInsets(-3, 20, 0);
		gd.addMessage("How much brighter a spot must be than the background right next to it, within one z-slice. See Help."
				+ "\nIn pixel intensities, so it depends on bit depth (8-bit: 0-255, 16-bit: 0-65535). Higher = fewer spots.",
				CiliaQPrepMain.INSTRUCTIONS_FONT);

		gd.setInsets(10, 0, 0);
		gd.addMessage("--- Advanced ---", CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addCheckbox("Auto minimum spot intensity", inst.autoMinIntensity);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("   override (used only if auto is off)", inst.minIntensity, 3);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Scale ratio (1.6 approximates LoG)", inst.scaleRatio, 2);

		gd.addHelp(HELP_URL);
		gd.showDialog();
		if (gd.wasCanceled()) throw new Exception("Spot detection settings dialog cancelled");

		inst.diameter = gd.getNextNumber();
		inst.variation = gd.getNextNumber();
		inst.sensitivity = gd.getNextNumber();
		inst.autoMinIntensity = gd.getNextBoolean();
		inst.minIntensity = gd.getNextNumber();
		inst.scaleRatio = gd.getNextNumber();
		return inst;
	}

	/** @return null when the settings can work, otherwise why not (without this the result is just empty or noise). */
	public String rejectionReason() {
		if (diameter <= 0.0)
			return "spot detection: the expected diameter must be above 0 px (got " + IJ.d2s(diameter, 2) + ")";
		if (variation < 0.0)
			return "spot detection: the diameter variation must be 0 or above (got " + IJ.d2s(variation, 2) + ")";
		if (sensitivity < 0.0)
			return "spot detection: the minimum spot contrast must be 0 or above (got " + IJ.d2s(sensitivity, 2) + ")";
		if (scaleRatio <= 1.0)
			return "spot detection: the scale ratio must be above 1 (got " + IJ.d2s(scaleRatio, 2)
					+ ") - at 1 the two Gaussians cancel and nothing is found";
		return null;
	}
	
	// ------------------------------------------------------------------ reporting

	public String report() {
		StringBuilder sb = new StringBuilder();
		sb.append("	Spot detection settings:\n");
		sb.append("		Expected object diameter (px)	").append(Numbers.exact(diameter, 3)).append("\n");
		sb.append("		Diameter variation (px)	").append(Numbers.exact(variation, 3)).append("\n");
		sb.append("		Minimum spot contrast	").append(Numbers.exact(sensitivity, 4)).append("\n");
		sb.append("		Prominence (DoG response of that contrast)	").append(Numbers.exact(prominence(), 4)).append("\n");
		sb.append("		Scale ratio	").append(Numbers.exact(scaleRatio, 3)).append("\n");
		double[] sigmas = scales();
		StringBuilder sigmaList = new StringBuilder();
		for (int i = 0; i < sigmas.length; i++) {
			if (i > 0) sigmaList.append(" / ");
			sigmaList.append(IJ.d2s(sigmas[i], 4));
		}
		sb.append("		Sigmas searched	").append(sigmaList.toString()).append("\n");
		sb.append("		Z consolidation	one spot per peak of the intensity profile through z\n");
		sb.append("		Auto minimum spot intensity	").append(autoMinIntensity).append("\n");
		if (!autoMinIntensity)
			sb.append("		Minimum spot intensity (override)	").append(Numbers.exact(minIntensity, 4)).append("\n");
		return sb.toString();
	}
}
