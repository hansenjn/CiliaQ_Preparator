package ciliaQ_Prep_jnh.expansion;

import ij.IJ;
import ij.gui.GenericDialog;

/**
 * CiliaQ Preparator - settings for EXPANSION from seeds.
 *
 * Same shape as the other per-channel settings objects (canny3d_thresholder.ProcessSettings,
 * ridge_detection.RidgeSettings, spot_detection.SpotSettings): built either by its own dialog or by
 * initDefault() when loading from a settings file.
 *
 * Notes on the defaults:
 *  - SEED SOURCE MUST MATCH THE STRUCTURE SHAPE. Use ridge seeds for filaments (cilia, flagella),
 *    spot seeds for punctate structures (basal bodies), and high-intensity cores as the fallback that
 *    needs no extra detector. Note, a blob detector run on a filament fires
 *    repeatedly along its length and produces many seeds for a single object.
 *
 *  - THE SEED THRESHOLD IS A THRESHOLD, NOT AN OFFSET. It is the intensity above which a voxel counts
 *    as a seed core, given exactly as the low (mask) threshold is - either an auto-threshold algorithm
 *    or a custom value. It is exposed the same way HYSTERESIS exposes its high threshold.
 *
 *  - MINIMUM PIECE SIZE guards against over-splitting. A split that would create a piece smaller than
 *    this is undone and the object is left whole. Too small a value keeps spurious splits; too large
 *    a value reverts genuine ones. The default is a reasonable middle ground, not a universal constant.
 *
 * @author Jan N. Hansen
 */
public class ExpansionSettings {
	static final String pluginName = ciliaQ_Prep_jnh.CiliaQPrepMain.PLUGIN_NAME;
	static final String pluginVersion = ciliaQ_Prep_jnh.CiliaQPrepMain.PLUGIN_VERSION;

	/** Seed sources, in the order they appear in the dialog. */
	public static final String SEED_RIDGE = "Ridge detection - filaments (recommended for cilia)";
	public static final String SEED_HIGH  = "High intensity cores (hysteresis-style)";
	public static final String SEED_SPOT  = "Spot detection - punctate (LoG/DoG)";
	public static String [] seedSources = { SEED_RIDGE, SEED_HIGH, SEED_SPOT };

	/** Threshold methods for the low (mask) threshold. Index 0 means "the value below is used". */
	public static String [] lowThrAlgorithms = { "Custom value", "Default", "IJ_IsoData", "Huang",
			"Intermodes", "IsoData", "Li", "MaxEntropy", "Mean", "MinError", "Minimum", "Moments",
			"Otsu", "Percentile", "RenyiEntropy", "Shanbhag", "Triangle", "Yen" };

	/** Simple section. */
	public String seedSource = SEED_RIDGE;
	public String lowThrAlgorithm = "Triangle";
	public double customLowThr = 0.0;
	public int minPieceSize = 200;				// voxels; measured, see class comment

	/** Only used when seedSource is SEED_HIGH: the seed (core) threshold, given exactly as the low one. */
	public String seedThrAlgorithm = "Otsu";
	public double customSeedThr = 0.0;

	/** Only used when seedSource is SEED_RIDGE - the two ridge parameters that matter for seeding. */
	public double ridgeWidth = 2.0;
	public double ridgeSensitivity = 1.0;

	/** Only used when seedSource is SEED_SPOT. */
	public double spotDiameter = 2.0;
	public double spotSensitivity = 0.5;

	private ExpansionSettings() {
		super();
	}

	public static ExpansionSettings initDefault() {
		return new ExpansionSettings();
	}

	public boolean customValueForLowThreshold() {
		return lowThrAlgorithm.equals(lowThrAlgorithms[0]);
	}

	public boolean customValueForSeedThreshold() {
		return seedThrAlgorithm.equals(lowThrAlgorithms[0]);
	}

	/** Build the ridge settings for seeding from the two exposed parameters. */
	public ciliaQ_Prep_jnh.ridge_detection.RidgeSettings toRidgeSettings() {
		ciliaQ_Prep_jnh.ridge_detection.RidgeSettings rs =
				ciliaQ_Prep_jnh.ridge_detection.RidgeSettings.initDefault();
		rs.lineWidth = ridgeWidth;
		rs.sensitivity = ridgeSensitivity;
		return rs;
	}

	/** Build the spot settings for seeding from the two exposed parameters. */
	public ciliaQ_Prep_jnh.spot_detection.SpotSettings toSpotSettings() {
		ciliaQ_Prep_jnh.spot_detection.SpotSettings ss =
				ciliaQ_Prep_jnh.spot_detection.SpotSettings.initDefault();
		ss.diameter = spotDiameter;
		ss.sensitivity = spotSensitivity;
		return ss;
	}

	// ------------------------------------------------------------------ dialog

	/**
	 * Note: Every seed parameter is shown at once rather than behind a second
	 * dialog per source - three numbers is less friction than a dialog chain, and the irrelevant ones
	 * are simply ignored, which the labels say.
	 */
	public static ExpansionSettings initByGD(String task) throws Exception {
		ExpansionSettings inst = new ExpansionSettings();

		GenericDialog gd = new GenericDialog("CiliaQ Preparator - expansion from seeds");
		gd.setInsets(0, 0, 0);
		gd.addMessage("Expansion from seeds - version " + pluginVersion,
				ciliaQ_Prep_jnh.CiliaQPrepMain.HEADING_FONT);
		gd.setInsets(10, 0, 0);
		gd.addMessage("Settings for " + task,
				ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addMessage("Separates TOUCHING objects. Crossing objects cannot be separated by intensity and"
				+ " are left as one object.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(-5, 0, 0);
		gd.addMessage("Output is a LABEL image: each object gets its own intensity value.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);

		gd.setInsets(10, 0, 0);
		gd.addChoice("Seed source", seedSources, inst.seedSource);
		gd.setInsets(0, 0, 0);
		gd.addChoice("Threshold for the object extent", lowThrAlgorithms, inst.lowThrAlgorithm);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("   value (used only if custom value is chosen)", inst.customLowThr, 3);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Minimum object size after splitting (voxels)", inst.minPieceSize, 0);
		gd.setInsets(-3, 20, 0);
		gd.addMessage("A split that would create anything smaller is undone, and the object stays whole.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);

		gd.setInsets(10, 0, 0);
		gd.addMessage("--- Seed parameters (only the chosen source's are used) ---",
				ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Filament width for ridge seeds (px)", inst.ridgeWidth, 2);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Contrast sensitivity for ridge seeds", inst.ridgeSensitivity, 3);
		gd.setInsets(0, 0, 0);
		gd.addChoice("Core threshold for high-intensity seeds", lowThrAlgorithms, inst.seedThrAlgorithm);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Manual core threshold (if custom value is chosen)",
				inst.customSeedThr, 3);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Diameter of spots to seed from (px)", inst.spotDiameter, 2);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Sensitivity for spot seeds (grey levels)", inst.spotSensitivity, 3);

		gd.showDialog();
		if (gd.wasCanceled()) throw new Exception("Expansion settings dialog cancelled");

		inst.seedSource = gd.getNextChoice();
		inst.lowThrAlgorithm = gd.getNextChoice();
		inst.customLowThr = gd.getNextNumber();
		inst.minPieceSize = (int) Math.round(gd.getNextNumber());
		inst.ridgeWidth = gd.getNextNumber();
		inst.ridgeSensitivity = gd.getNextNumber();
		inst.seedThrAlgorithm = gd.getNextChoice();
		inst.customSeedThr = gd.getNextNumber();
		inst.spotDiameter = gd.getNextNumber();
		inst.spotSensitivity = gd.getNextNumber();
		return inst;
	}

	/**
	 * @return a warning when the chosen seed source does not match the structure the other parameters
	 * describe, or null. Cannot be done in the dialog for the same reason as the spot sensitivity
	 * warning: nothing about the image is known there.
	 */
	public String seedSourceWarning() {
		if (seedSource.equals(SEED_SPOT) && spotDiameter > 6.0)
			return "expansion: spot seeds with an expected diameter of " + IJ.d2s(spotDiameter, 1)
					+ " px - spot seeds are for punctate structures like basal bodies; on filaments a blob detector"
					+ " fires repeatedly along the length (measured 6-29 seeds per object)";
		if (minPieceSize <= 0)
			return "expansion: the minimum object size is " + minPieceSize + ", so the guard is off"
					+ " - crossing objects will be split into slivers instead of being left whole";
		return null;
	}

	// ------------------------------------------------------------------ reporting

	public String report() {
		StringBuilder sb = new StringBuilder();
		sb.append("	Expansion from seeds settings:\n");
		sb.append("		Seed source	").append(seedSource).append("\n");
		sb.append("		Threshold for the object extent	").append(lowThrAlgorithm).append("\n");
		if (customValueForLowThreshold())
			sb.append("		Manually selected threshold	").append(IJ.d2s(customLowThr, 4)).append("\n");
		sb.append("		Minimum object size after splitting (voxels)	").append(minPieceSize).append("\n");
		if (seedSource.equals(SEED_RIDGE)) {
			sb.append("		Ridge seed - expected filament width (px)	")
					.append(IJ.d2s(ridgeWidth, 2)).append("\n");
			sb.append("		Ridge seed - contrast sensitivity	")
					.append(IJ.d2s(ridgeSensitivity, 3)).append("\n");
		} else if (seedSource.equals(SEED_HIGH)) {
			sb.append("		High core seed - seed threshold	").append(seedThrAlgorithm).append("\n");
			if (customValueForSeedThreshold())
				sb.append("		High core seed - manually selected seed threshold	")
						.append(IJ.d2s(customSeedThr, 4)).append("\n");
		} else if (seedSource.equals(SEED_SPOT)) {
			sb.append("		Spot seed - expected diameter (px)	")
					.append(IJ.d2s(spotDiameter, 2)).append("\n");
			sb.append("		Spot seed - sensitivity (grey levels)	")
					.append(IJ.d2s(spotSensitivity, 3)).append("\n");
		}
		return sb.toString();
	}
}
