package ciliaQ_Prep_jnh.fraction_threshold;

import java.awt.Font;

import ij.IJ;
import ij.gui.GenericDialog;

/**
 * CiliaQ Preparator - settings for the two FRACTION-OF-INTENSITY-RANGE threshold methods.
 *
 *   FRACTION of intensity range        thr    = bg + f_g * (signal - bg)          one global threshold
 *   LOCAL FRACTION of intensity range  thr(x) = max(global, bg_l + f_l * (signal_l - bg_l))   per-pixel surface
 *
 * Both are SIGNAL-anchored: the threshold sits at a fraction of the way from the estimated background
 * level up to the estimated signal level. This is a robust variant of percent-of-maximum (global) and
 * of a local adaptive threshold (local).
 *
 * Notes on the defaults:
 *
 *  - The global fraction f_g defaults low, and errs toward INCLUSION: over-inclusion is cleaned up later
 *    by the minimum-cilium-size filter, whereas over-exclusion silently deletes structures. Setting f_g
 *    too high can push the threshold above a structure's lowest intensity and make whole objects vanish.
 *
 *  - The local method uses a larger fraction f_l within each tile, floored at the global threshold (see
 *    FractionThreshold.local for why the floor matters).
 *
 *  - NO noise-based (MAD / standard-deviation) floor is used. On background-free or background-subtracted
 *    data the noise estimate is 0, so such a floor would collapse to "any non-zero voxel" and do nothing.
 *
 * Added in V0.2.1.
 * 
 * @author Jan N. Hansen
 */
public class FractionSettings {
	static final String pluginName = ciliaQ_Prep_jnh.CiliaQPrepMain.PLUGIN_NAME;
	static final String pluginVersion = ciliaQ_Prep_jnh.CiliaQPrepMain.PLUGIN_VERSION;

	/** The two dropdown entries, sitting beside CUSTOM threshold and HYSTERESIS threshold. */
	public static final String GLOBAL = "FRACTION of intensity range";
	public static final String LOCAL  = "LOCAL FRACTION of intensity range";

	public static boolean isFractionMethod(String alg) {
		return GLOBAL.equals(alg) || LOCAL.equals(alg);
	}

	public double fGlobal = 0.05;
	public double fLocal = 0.20;
	public int tileSize = 64;
	public double bgPercentile = 50.0;

	/**
	 * Two signal percentiles, not one, and this is not an oversight.
	 *
	 * The global statistic is taken over the whole image, where the 99.9th percentile is a stable
	 * extreme. A 64 x 64 tile holds 4096 pixels, so its 99.9th percentile is the 4th brightest pixel in
	 * the tile - an estimate driven by single hot pixels. The local method therefore uses p99 (the 41st
	 * brightest), which is what D6c calibrated.
	 */
	public double signalPercentileGlobal = 99.9;
	public double signalPercentileLocal = 99.0;

	private FractionSettings() {
		super();
	}

	public static FractionSettings initDefault() {
		return new FractionSettings();
	}

	// ------------------------------------------------------------------ dialog

	/**
	 * Secondary dialog, the same pattern CANNY 3D already uses. Opened only when one of the two methods
	 * is selected.
	 */
	public static FractionSettings initByGD(String task, boolean local) throws Exception {
		FractionSettings inst = new FractionSettings();

		GenericDialog gd = new GenericDialog(pluginName + " - fraction-of-range threshold");
		gd.setInsets(0, 0, 0);
		gd.addMessage((local ? LOCAL : GLOBAL) + " - version " + pluginVersion,
				ciliaQ_Prep_jnh.CiliaQPrepMain.HEADING_FONT);
		gd.setInsets(10, 0, 0);
		gd.addMessage("Settings for " + task,
				ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addMessage("Leave these at the defaults unless you have calibrated them for your data.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(-5, 0, 0);
		gd.addMessage("See https://github.com/hansenjn/CiliaQ_Preparator/wiki for how to calibrate them.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(-5, 0, 0);
		gd.addMessage("If your data is speckly, add the median filter before changing the fraction.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);

		gd.setInsets(10, 0, 0);
		if (local) {
			gd.addNumericField("Local fraction f (0-1)", inst.fLocal, 3);
			gd.setInsets(0, 0, 0);
			gd.addNumericField("Tile size (px)", inst.tileSize, 0);
			gd.setInsets(0, 0, 0);
			gd.addNumericField("Global fraction f (used as the lower bound)", inst.fGlobal, 3);
			gd.setInsets(0, 0, 0);
			gd.addNumericField("Background percentile", inst.bgPercentile, 2);
			gd.setInsets(0, 0, 0);
			gd.addNumericField("Signal percentile (per tile)", inst.signalPercentileLocal, 2);
		} else {
			gd.addNumericField("Fraction f (0-1)", inst.fGlobal, 3);
			gd.setInsets(0, 0, 0);
			gd.addNumericField("Background percentile", inst.bgPercentile, 2);
			gd.setInsets(0, 0, 0);
			gd.addNumericField("Signal percentile", inst.signalPercentileGlobal, 2);
		}

		gd.showDialog();
		if (gd.wasCanceled()) throw new Exception("Fraction threshold settings dialog cancelled");

		if (local) {
			inst.fLocal = gd.getNextNumber();
			inst.tileSize = (int) Math.round(gd.getNextNumber());
			inst.fGlobal = gd.getNextNumber();
			inst.bgPercentile = gd.getNextNumber();
			inst.signalPercentileLocal = gd.getNextNumber();
		} else {
			inst.fGlobal = gd.getNextNumber();
			inst.bgPercentile = gd.getNextNumber();
			inst.signalPercentileGlobal = gd.getNextNumber();
		}
		return inst;
	}

	/** @return a warning about a setting outside the calibrated range, or null. */
	public String rangeWarning(boolean local) {
		double f = local ? fLocal : fGlobal;
		if (f <= 0.0 || f >= 1.0)
			return "fraction threshold: f = " + IJ.d2s(f, 3) + " is outside 0-1, so the threshold is"
					+ " either the background level or above the signal";
		if (!local && fGlobal > 0.10)
			return "fraction threshold: f = " + IJ.d2s(fGlobal, 3) + " is above the calibrated working"
					+ " range 0.05-0.10 - on thin structures this can lose everything at once"
					+ " (measured 123 objects -> 1 at f = 0.15)";
		if (tileSize < 8)
			return "fraction threshold: a tile of " + tileSize + " px holds too few pixels for a"
					+ " percentile to mean anything";
		return null;
	}

	// ------------------------------------------------------------------ reporting

	public String report(boolean local) {
		StringBuilder sb = new StringBuilder();
		sb.append("	Fraction-of-range threshold settings:\n");
		if (local) {
			sb.append("		Local fraction f	").append(IJ.d2s(fLocal, 4)).append("\n");
			sb.append("		Tile size (px)	").append(tileSize).append("\n");
			sb.append("		Global fraction f (lower bound)	").append(IJ.d2s(fGlobal, 4)).append("\n");
			sb.append("		Background percentile	").append(IJ.d2s(bgPercentile, 2)).append("\n");
			sb.append("		Signal percentile (per tile)	")
					.append(IJ.d2s(signalPercentileLocal, 2)).append("\n");
		} else {
			sb.append("		Fraction f	").append(IJ.d2s(fGlobal, 4)).append("\n");
			sb.append("		Background percentile	").append(IJ.d2s(bgPercentile, 2)).append("\n");
			sb.append("		Signal percentile	").append(IJ.d2s(signalPercentileGlobal, 2)).append("\n");
		}
		return sb.toString();
	}
}
