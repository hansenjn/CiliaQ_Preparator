package ciliaQ_Prep_jnh.local_threshold;

import ij.IJ;
import ij.gui.GenericDialog;

/**
 * CiliaQ Preparator - settings for the in-house Phansalkar local threshold.
 *
 * This method tackles variation in FOREGROUND brightness. Rolling-ball and divide-by-background correct the *background*
 * but, if one cilium is far brighter than another, a global threshold may either loses the dim one or inflates the bright one.
 *
 * Phansalkar's p*exp(-q*mean) term raises the threshold multiplicatively in dim regions, which
 * suppresses noise in background-only windows - avoiding the failure of simpler local thresholds
 * (Mean +/- C, mean + k*sd, Niblack) on sparse structures. Phansalkar is tuned for low-contrast staining.
 *
 * Added in v0.2.1.
 *
 * @author Jan N. Hansen
 */
public class PhansalkarSettings {
	static final String pluginName = ciliaQ_Prep_jnh.CiliaQPrepMain.PLUGIN_NAME;
	static final String pluginVersion = ciliaQ_Prep_jnh.CiliaQPrepMain.PLUGIN_VERSION;

	/** The dropdown entry, beside the other named methods. */
	public static final String NAME = "PHANSALKAR local threshold";

	/** Radius in PIXELS, per D4c-units. */
	public double radius = 15.0;

	/** Phansalkar's four constants; the published defaults. */
	public double k = Phansalkar.K;
	public double r = Phansalkar.R;
	public double p = Phansalkar.P;
	public double q = Phansalkar.Q;

	private PhansalkarSettings() {
		super();
	}

	public static PhansalkarSettings initDefault() {
		return new PhansalkarSettings();
	}

	// ------------------------------------------------------------------ dialog

	public static PhansalkarSettings initByGD(String task) throws Exception {
		PhansalkarSettings settingsInstance = new PhansalkarSettings();

		GenericDialog gd = new GenericDialog(pluginName + " - Phansalkar local threshold");
		gd.setInsets(0, 0, 0);
		gd.addMessage(NAME + " - version " + pluginVersion,
				ciliaQ_Prep_jnh.CiliaQPrepMain.HEADING_FONT);
		gd.setInsets(10, 0, 0);
		gd.addMessage("Settings for " + task,
				ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addMessage("Use this when the OBJECTS themselves differ in brightness across the image.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(-5, 0, 0);
		gd.addMessage("Background variation alone is better handled by 'Subtract Background'.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);

		gd.setInsets(10, 0, 0);
		gd.addNumericField("Radius (pixels)", settingsInstance.radius, 2);
		gd.setInsets(-3, 20, 0);
		gd.addMessage("Must be larger than the objects, or an object becomes its own background."
				+ " Measured 15-50 px on the test data.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);

		gd.setInsets(10, 0, 0);
		gd.addMessage("--- Advanced (Phansalkar constants) ---",
				ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addMessage("Leave these at the published defaults unless you have calibrated them.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(5, 0, 0);
		gd.addNumericField("k", settingsInstance.k, 4);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("r", settingsInstance.r, 4);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("p", settingsInstance.p, 4);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("q", settingsInstance.q, 4);

		gd.showDialog();
		if (gd.wasCanceled()) throw new Exception("Phansalkar settings dialog cancelled");

		settingsInstance.radius = gd.getNextNumber();
		settingsInstance.k = gd.getNextNumber();
		settingsInstance.r = gd.getNextNumber();
		settingsInstance.p = gd.getNextNumber();
		settingsInstance.q = gd.getNextNumber();
		return settingsInstance;
	}

	/** @return a warning about a setting that cannot work, or null. */
	public String rangeWarning() {
		if (radius < 2.0)
			return "Phansalkar: radius " + IJ.d2s(radius, 2) + " px is smaller than any real"
					+ " neighbourhood, so the local mean is essentially the pixel itself";
		if (r == 0.0)
			return "Phansalkar: r = 0 divides by zero in the k*((sd/r) - 1) term";
		return null;
	}

	// ------------------------------------------------------------------ reporting

	public String report() {
		StringBuilder sb = new StringBuilder();
		sb.append("	Phansalkar local threshold settings:\n");
		/*	Keys are prefixed rather than bare "k"/"r"/"p"/"q": the settings reader matches keys by
		 *	PREFIX, so a bare single letter would be shadowed by any future key starting with it. */
		sb.append("		Radius (px)	").append(IJ.d2s(radius, 3)).append("\n");
		sb.append("		Constant k	").append(IJ.d2s(k, 4)).append("\n");
		sb.append("		Constant r	").append(IJ.d2s(r, 4)).append("\n");
		sb.append("		Constant p	").append(IJ.d2s(p, 4)).append("\n");
		sb.append("		Constant q	").append(IJ.d2s(q, 4)).append("\n");
		return sb.toString();
	}
}
