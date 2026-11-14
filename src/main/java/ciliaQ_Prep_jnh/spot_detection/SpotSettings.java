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
 *  - SENSITIVITY IS IN ABSOLUTE GREY LEVELS, never a percentage. Absolute levels transfer across images
 *    far better than percentage-of-range or percentage-of-type-max forms, because noise is a property
 *    of the detector, not of how bright the sample is.
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

	/** Simple section. */
	public double diameter = 5.0;			// pixels, expected object diameter
	public double variation = 1.0;			// pixels, spread of diameters -> multi-scale range
	public double sensitivity = 500.0;		// ABSOLUTE grey levels of prominence, never a percentage

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
		gd.addMessage("Set diameter for your pixel size, then adjust sensitivity (see Help).",
				CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Sensitivity (grey levels)", inst.sensitivity, 3);
		gd.setInsets(-3, 20, 0);
		gd.addMessage("Absolute grey levels. Higher sensitivity = stricter = fewer spots.",
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
			return "spot detection: the sensitivity must be 0 or above (got " + IJ.d2s(sensitivity, 2) + ")";
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
		sb.append("		Sensitivity (grey levels)	").append(Numbers.exact(sensitivity, 4)).append("\n");
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
