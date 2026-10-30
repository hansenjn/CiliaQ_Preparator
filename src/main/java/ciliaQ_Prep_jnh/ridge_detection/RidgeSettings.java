package ciliaQ_Prep_jnh.ridge_detection;

import java.awt.AWTEvent;
import java.util.Vector;

import ij.gui.GenericDialog;
import ij.gui.DialogListener;
import ij.IJ;

/**
 * CiliaQ Preparator - settings for RIDGE DETECTION (Steger line detector).
 *
 * Holds the per-channel parameters for detecting FILAMENTOUS structures (cilia, flagella) with the
 * Steger ridge/line detector. Follows the same pattern as the other per-channel settings objects
 * (ProcessSettings, SpotSettings, ExpansionSettings): an instance is produced either by its own dialog
 * (initByGD) or by initDefault() when settings are loaded from a metadata file or a macro.
 *
 * SIMPLE vs ADVANCED PARAMETERS.
 * The user is expected to set only three intuitive values - the expected filament width, a contrast
 * sensitivity, and a minimum length. The Steger detector itself, however, needs a Gaussian sigma and
 * an upper/lower threshold pair that act on the image's SECOND-DERIVATIVE response, not on intensity.
 * These are DERIVED from the simple values plus the channel's intensity statistics (see deriveFrom),
 * and are exposed in an "advanced" section only so an expert can override them.
 *
 * WHY THE DERIVATION LIVES HERE, NOT IN THE DIALOG.
 * Fiji's own Ridge Detection plugin computes sigma and the thresholds inside its dialog listener. That
 * listener never fires in macro or headless mode, so a macro caller would silently receive unconverted
 * defaults. To avoid that, deriveFrom(...) is a plain method that the PROCESSING code calls
 * unconditionally; the dialog listener calls it only to refresh the greyed advanced fields for a user
 * who is editing interactively. Both paths therefore produce the same numbers.
 *
 * ROUND-TRIP.
 * report() writes these settings into the _CQP.txt metadata file as tab-indented key/value lines, and
 * the reader in CiliaQPrepMain parses them back. The derived values are written only once they exist
 * (after deriveFrom has run for a channel), so the metadata never states a threshold that was not used.
 * 
 * STEGER LINE DETECTOR
 * This technique bases on
 *  
 * > Steger, C., 1998. An unbiased detector of curvilinear structures.
 * IEEE Transactions on Pattern Analysis and Machine Intelligence, 20(2), pp.113–125.
 * 
 * and follows ImageJ's implementation: https://imagej.net/plugins/ridge-detection.
 *
 * Added in v0.2.1.
 *
 * @author Jan N. Hansen
 */
public class RidgeSettings {

	static final String pluginName = ciliaQ_Prep_jnh.CiliaQPrepMain.PLUGIN_NAME;
	static final String pluginVersion = ciliaQ_Prep_jnh.CiliaQPrepMain.PLUGIN_VERSION;
	
	/** Simple section - what the user is expected to set. */
	public double lineWidth = 2.0;			// pixels, IN THE PREPROCESSED IMAGE
	public double sensitivity = 1.0;		// fraction of the signal range used as line contrast
	public double minLength = 10.0;			// pixels
	public boolean resolveCrossings = true;	// OverlapOption.SLOPE when true

	/** Advanced section - derived from the above unless the user overrides them. */
	public double sigma = 0.0;
	public double upperThreshold = 0.0;
	public double lowerThreshold = 0.0;
	public double contrastHigh = 0.0;
	public double contrastLow = 0.0;
	public double maxLength = 0.0;			// 0 = unlimited
	public boolean darkLine = false;
	public boolean correctPosition = true;
	public boolean estimateWidth = true;
	public boolean extendLine = true;

	/** True once the user has typed into an advanced field, so derivation must not overwrite it. */
	public boolean advancedOverride = false;

	/**
	 * Ratio of lower to upper threshold (the contrast->threshold conversion is linear). A nonzero lower
	 * threshold stops lines extending indefinitely into faint background. 0.6 is a starting value close
	 * to the usual hysteresis convention of one half, not a calibrated constant.
	 */
	public static final double LOW_TO_HIGH = 0.6;

	private RidgeSettings() {
		super();
	}

	public static RidgeSettings initDefault() {
		return new RidgeSettings();
	}

	// ------------------------------------------------------------------ the conversion

	/** Gaussian sigma from the expected line width, matching Fiji's Ridge Detection dialog. */
	public static double sigmaForWidth(double lineWidth) {
		return lineWidth / (2.0 * Math.sqrt(3.0)) + 0.5;
	}

	/**
	 * Contrast -> threshold, matching how Fiji's Ridge Detection computes it. The 0.17 factor and the
	 * floor() are both essential; the thresholds act on the SECOND-DERIVATIVE response, not on
	 * intensity, so they cannot be derived as a simple fraction of the intensity range.
	 */
	public static double thresholdForContrast(double lineWidth, double contrast) {
		double s = sigmaForWidth(lineWidth);
		double halfWidth = lineWidth / 2.0;
		double raw = Math.abs(-2.0 * contrast * halfWidth
				/ (Math.sqrt(2.0 * Math.PI) * s * s * s)
				* Math.exp(-(halfWidth * halfWidth) / (2.0 * s * s)));
		return 0.17 * Math.floor(raw);
	}

	/**
	 * @return true once sigma and the thresholds hold values that were actually used, i.e. after
	 * deriveFrom has run or because the user supplied them. sigma is the marker: it is 0 in a fresh
	 * settings object and strictly positive afterwards, since sigmaForWidth adds 0.5.
	 */
	public boolean isDerived() {
		return sigma > 0.0;
	}

	/**
	 * Fill in sigma / thresholds from the simple settings and the channel statistics.
	 * MUST be called from the processing code, not only from the dialog, or macro runs get defaults.
	 *
	 * @param median			median of the channel, on the scope the user chose for stack processing
	 * @param highPercentile	a high percentile of the same channel (p99.9 is what was calibrated)
	 */
	public void deriveFrom(double median, double highPercentile) {
		if (advancedOverride) return;
		double signalRange = Math.max(0.0, highPercentile - median);
		this.contrastHigh = sensitivity * signalRange;
		this.contrastLow = LOW_TO_HIGH * this.contrastHigh;
		this.sigma = sigmaForWidth(lineWidth);
		double hUpper = darkLine ? (255.0 - contrastLow) : contrastHigh;
		double hLower = darkLine ? (255.0 - contrastHigh) : contrastLow;
		this.upperThreshold = thresholdForContrast(lineWidth, hUpper);
		this.lowerThreshold = thresholdForContrast(lineWidth, hLower);
	}

	/**
	 * Refuse parameter combinations that cannot work.
	 *
	 * The contrast check is in CONTRAST units (which match MAD) and only applies when MAD > 0, because a
	 * MAD-anchored guard is degenerate on background-free data (MAD = 0) and would never fire there. It
	 * must not compare against upperThreshold, which lives in SECOND-DERIVATIVE units, not intensity. The
	 * guard that actually catches a runaway detection is RidgeSegmenter.LINE_CAP_DIVISOR, anchored on the
	 * failure mode (too many Line objects for the heap) rather than on any statistic.
	 *
	 * @return null when acceptable, otherwise the reason to report and abort with.
	 */
	public String rejectionReason(double madOfChannel) {
		if (upperThreshold <= 0.0)
			return "ridge detection: derived upper threshold is 0 - increase 'contrast sensitivity' "
					+ "or check that the channel carries signal";
		if (madOfChannel > 0.0 && contrastHigh < 2.0 * madOfChannel)
			return "ridge detection: the line contrast this would look for (" + IJ.d2s(contrastHigh, 2)
					+ ") is below twice the channel noise level (" + IJ.d2s(2.0 * madOfChannel, 2)
					+ ") - raise 'contrast sensitivity', or the detector will trace noise";
		return null;
	}

	// ------------------------------------------------------------------ dialog

	public static RidgeSettings initByGD(String task) throws Exception {
		final boolean[] updating = { false };
		final double[] lastW   = { Double.NaN };
		final double[] lastSens = { Double.NaN };
		final boolean[] lastDark = { false };
		
		final RidgeSettings inst = new RidgeSettings();
		inst.deriveFrom(0.0, 255.0);	// so the advanced fields show something sensible before any image

		final GenericDialog gd = new GenericDialog(pluginName + " - ridge detection settings");
		gd.setInsets(0, 0, 0);
		gd.addMessage("Ridge detection (Steger line detector) - version " + pluginVersion,
				ciliaQ_Prep_jnh.CiliaQPrepMain.HEADING_FONT);
		gd.setInsets(10, 0, 0);
		gd.addMessage("Settings for " + task, ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addMessage("Intended for FILAMENTOUS structures (cilia, flagella). Usable on any channel, but for",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(-5, 0, 0);
		gd.addMessage("punctate structures such as basal bodies prefer LoG/DoG spot detection.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);

		gd.setInsets(10, 0, 0);
		gd.addMessage("--- Simple ---", ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Expected filament width (pixels)", inst.lineWidth, 2);
		gd.setInsets(-3, 20, 0);
		gd.addMessage("Width IN THE PREPROCESSED IMAGE - measure it after any blur or background subtraction.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Contrast sensitivity", inst.sensitivity, 2);
		gd.setInsets(-3, 20, 0);
		gd.addMessage("Higher = stricter (only high-contrast filaments); lower picks up more background."
				+ " You can increase above 1.",
				ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Minimum filament length (pixels)", inst.minLength, 1);
		gd.setInsets(0, 0, 0);
		gd.addCheckbox("Resolve crossings (recommended)", inst.resolveCrossings);

		gd.setInsets(10, 0, 0);
		gd.addMessage("--- Advanced (derived from the above; edit to override) ---",
				ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Sigma", inst.sigma, 3);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Upper threshold", inst.upperThreshold, 3);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Lower threshold", inst.lowerThreshold, 3);
		gd.setInsets(0, 0, 0);
		gd.addNumericField("Maximum filament length (0 = unlimited)", inst.maxLength, 1);
		gd.setInsets(0, 0, 0);
		gd.addCheckbox("Dark line (dark filaments on a bright background)", inst.darkLine);
		gd.setInsets(0, 0, 0);
		gd.addCheckbox("Correct position", inst.correctPosition);
		gd.setInsets(0, 0, 0);
		gd.addCheckbox("Estimate width", inst.estimateWidth);
		gd.setInsets(0, 0, 0);
		gd.addCheckbox("Extend line", inst.extendLine);

		/*
		 * Live cross-update: editing a Simple field refreshes the derived Advanced fields.
		 * deriveFrom(...) is called again by the processing
		 * code, so a macro run (no dialog, no listener) still gets derived values.
		 */
		gd.addDialogListener(new DialogListener() {
		    public boolean dialogItemChanged(GenericDialog d, AWTEvent e) {
		        if(updating[0])	return true;
		        Vector<?> nf = d.getNumericFields();
		        if (nf == null || nf.size() < 7) return true;
		        try {
		            double w = Double.parseDouble(((java.awt.TextField) nf.get(0)).getText());
		            double sens = Double.parseDouble(((java.awt.TextField) nf.get(1)).getText());
		            if (w <= 0 || sens <= 0) return true;

		            java.util.Vector<?> cb = d.getCheckboxes();
		            boolean dark = (cb != null && cb.size() >= 2)
		                    && ((java.awt.Checkbox) cb.get(1)).getState();

		            // Nothing that affects the preview changed (e.g. this was just a focus move,
		            // or an edit to minLength/maxLength) -> do NOT touch the fields. This is what
		            // stops the focus-change stall: setText is never called on a focus-only event.
		            if(w == lastW[0] && sens == lastSens[0] && dark == lastDark[0]) return true;
		            lastW[0] = w; lastSens[0] = sens; lastDark[0] = dark;

		            RidgeSettings preview = new RidgeSettings();
		            preview.lineWidth = w;
		            preview.sensitivity = sens;
		            preview.darkLine = dark;
		            preview.deriveFrom(0.0, 255.0);

		            updating[0] = true;
		            try {
		                ((java.awt.TextField) nf.get(3)).setText(IJ.d2s(preview.sigma, 3));
		                ((java.awt.TextField) nf.get(4)).setText(IJ.d2s(preview.upperThreshold, 3));
		                ((java.awt.TextField) nf.get(5)).setText(IJ.d2s(preview.lowerThreshold, 3));
		            } finally {
		                updating[0] = false;
		            }
		        } catch (Exception ignored) {
		            // partially typed number - leave the fields as they are
		        }
		        return true;
		    }
		});

		gd.showDialog();
		if (gd.wasCanceled()) throw new Exception("Ridge detection settings dialog cancelled");

		inst.lineWidth = gd.getNextNumber();
		inst.sensitivity = gd.getNextNumber();
		inst.minLength = gd.getNextNumber();
		inst.resolveCrossings = gd.getNextBoolean();
		double sigmaTyped = gd.getNextNumber();
		double upperTyped = gd.getNextNumber();
		double lowerTyped = gd.getNextNumber();
		inst.maxLength = gd.getNextNumber();
		inst.darkLine = gd.getNextBoolean();
		inst.correctPosition = gd.getNextBoolean();
		inst.estimateWidth = gd.getNextBoolean();
		inst.extendLine = gd.getNextBoolean();

		/*
		 * An advanced value counts as an override only if it differs from what the simple settings would
		 * derive. Comparing against the preview keeps "the user did not touch it" from being mistaken
		 * for an override just because the field was populated.
		 */
		RidgeSettings preview = new RidgeSettings();
		preview.lineWidth = inst.lineWidth;
		preview.sensitivity = inst.sensitivity;
		preview.darkLine = inst.darkLine;
		preview.deriveFrom(0.0, 255.0);
		if (Math.abs(sigmaTyped - preview.sigma) > 1e-4
				|| Math.abs(upperTyped - preview.upperThreshold) > 1e-4
				|| Math.abs(lowerTyped - preview.lowerThreshold) > 1e-4) {
			inst.advancedOverride = true;
			inst.sigma = sigmaTyped;
			inst.upperThreshold = upperTyped;
			inst.lowerThreshold = lowerTyped;
		}
		return inst;
	}

	// ------------------------------------------------------------------ reporting

	/** Tab-indented lines for _CQP.txt, matching how the rest of the Preparator reports settings. */
	public String report() {
		StringBuilder sb = new StringBuilder();
		sb.append("	Ridge detection settings:\n");
		sb.append("		Expected filament width (px)	").append(IJ.d2s(lineWidth, 2)).append("\n");
		sb.append("		Contrast sensitivity	").append(IJ.d2s(sensitivity, 3)).append("\n");
		sb.append("		Minimum filament length (px)	").append(IJ.d2s(minLength, 1)).append("\n");
		sb.append("		Maximum filament length (px)	")
				.append(maxLength <= 0 ? "unlimited" : IJ.d2s(maxLength, 1)).append("\n");
		sb.append("		Resolve crossings	").append(resolveCrossings).append("\n");
		/*
		 * The five Steger values below are DERIVED from the image (deriveFrom) unless the user
		 * overrode them. report() is called from two places at different times:
		 *   - the settings section of the metadata file, written BEFORE any channel is segmented;
		 *   - the per-channel processing log, written AFTER deriveFrom for that channel.
		 * In the first case they are all still zero, and printing "Upper threshold 0.0000" there would
		 * state a value that was never used. So print them only once they exist.
		 */
		if(isDerived()) {
			sb.append("		Sigma	").append(IJ.d2s(sigma, 4)).append("\n");
			sb.append("		Upper threshold	").append(IJ.d2s(upperThreshold, 4)).append("\n");
			sb.append("		Lower threshold	").append(IJ.d2s(lowerThreshold, 4)).append("\n");
			sb.append("		High contrast	").append(IJ.d2s(contrastHigh, 3)).append("\n");
			sb.append("		Low contrast	").append(IJ.d2s(contrastLow, 3)).append("\n");
		} else {
			sb.append("		Sigma / thresholds / contrasts	")
					.append("derived from the image intensities of each channel during processing")
					.append(" - see the effective values in the log for that channel\n");
		}
		sb.append("		Advanced values overridden by user	").append(advancedOverride).append("\n");
		sb.append("		Dark line	").append(darkLine).append("\n");
		/*	Three separate lines, not one combined "a / b / c" line. The settings reader parses this
		 *	block as key/value pairs, so a combined line cannot be read back into three booleans - and
		 *	before this was split, all three were silently lost on every settings-file round trip. */
		sb.append("		Correct position	").append(correctPosition).append("\n");
		sb.append("		Estimate width	").append(estimateWidth).append("\n");
		sb.append("		Extend line	").append(extendLine).append("\n");
		return sb.toString();
	}
}
