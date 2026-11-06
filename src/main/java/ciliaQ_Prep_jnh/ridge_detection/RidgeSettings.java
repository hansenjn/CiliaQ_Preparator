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
 * Z-STACKS AND CLEAN-UP (v0.2.1). The z method decides how the lines of the planes become objects: classic 2D
 * (every plane a line is found in, joined by overlap), 2D in-focus plane (each detection only in its in-focus plane),
 * or 3D (in-focus plane plus pairing of crossing branches across planes). The clean-up rules (RidgeCleanup) then
 * keep lines apart that belong to different filaments: copy (out-of-focus copies, in-focus and 3D only), kink,
 * branch, crossing, parallel with the turn release, in that order. Defaults: in-focus; branch and crossing on;
 * copy, kink and parallel off. On a single plane all three z methods give the same result. Last, once the objects are
 * made, short pieces (within 1 width of a longer object and reaching less than 2 widths from there, such as the forks
 * Steger draws at a broad cilium tip) join that object by default; they can instead be dropped, or kept as objects of
 * their own (RidgeShortPieces; in in-focus and 3D a standing cilium is never a short piece).
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

	/** z-stacks: how the lines of the planes become objects. Same result on a single plane. */
	public static final String Z_CLASSIC = "Classic 2D (all planes)", Z_INFOCUS = "2D in-focus plane",
			Z_3D = "3D (in-focus plane and crossing pairing)";
	public static final String [] Z_METHODS = { Z_CLASSIC, Z_INFOCUS, Z_3D };
	public String zMethod = Z_INFOCUS;

	/** Clean-up rules, in the order they run (see RidgeCleanup). */
	public boolean copyRule = false;		// drop out-of-focus copies (in-focus and 3D only)
	public double copyRatio = 4.0;			// a copy is dropped when a point this much brighter lies in another plane beside it
	
	public boolean kinkRule = false;		// cut lines at sharp bends (v0.2.1)
	public double kinkAngle = 25.0;			// degrees
	
	public boolean branchRule = true;
	/** step 6: what happens to a short piece next to a filament, such as the fork Steger draws at a broad cilium tip */
	public static final String SHORT_JOIN = "join their filament", SHORT_DROP = "drop", SHORT_APART = "keep apart";
	public static final String [] SHORT_CHOICES = { SHORT_JOIN, SHORT_DROP, SHORT_APART };
	public String shortPieces = SHORT_JOIN;
	public boolean crossingRule = true;
	public boolean parallelRule = false;
	public boolean turnRelease = true;		// sub-option of the parallel rule

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
	public double zProfileSmoothing = 0.0;	// px; 0 = automatic: sigmaForWidth(lineWidth)
	public double turnAngle = 25.0;			// turn release: degrees between a runner's end and the line it turns onto
	public double turnTangent = 2.0;		// turn release: directions measured over this many line widths
	public double turnContinuity = 0.9;		// turn release: brightest path at least this x the runners' ridge level

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

	/** Macro token of the z method (no spaces): classic, in-focus, 3D. */
	public String zMethodToken() {
		return Z_CLASSIC.equals(zMethod) ? "classic" : Z_3D.equals(zMethod) ? "3D" : "in-focus";
	}

	/** The z method for a macro token (case-insensitive: classic, in-focus, 3D), or null if unknown. */
	public static String zMethodFromToken(String token) {
		if (token == null) return null;
		String t = token.trim().toLowerCase(java.util.Locale.ROOT);	// ROOT: in a Turkish locale "I" would not lower to "i"
		if (t.equals("classic")) return Z_CLASSIC;
		if (t.equals("in-focus") || t.equals("infocus")) return Z_INFOCUS;
		if (t.equals("3d")) return Z_3D;
		return null;
	}

	/** Macro token of the short-piece handling (no spaces): join, drop, apart. */
	public String shortPiecesToken() {
		return SHORT_DROP.equals(shortPieces) ? "drop" : SHORT_APART.equals(shortPieces) ? "apart" : "join";
	}

	/** The short-piece handling for a macro token (case-insensitive: join, drop, apart), or null if unknown. */
	public static String shortPiecesFromToken(String token) {
		if (token == null) return null;
		String t = token.trim().toLowerCase(java.util.Locale.ROOT);
		if (t.equals("join")) return SHORT_JOIN;
		if (t.equals("drop")) return SHORT_DROP;
		if (t.equals("apart") || t.equals("keep-apart")) return SHORT_APART;
		return null;
	}

	/** The step-6 mode for RidgeShortPieces (APART, JOIN or DROP). */
	int shortPiecesMode() {
		return SHORT_DROP.equals(shortPieces) ? RidgeShortPieces.DROP : SHORT_APART.equals(shortPieces) ? RidgeShortPieces.APART : RidgeShortPieces.JOIN;
	}

	/** The z-profile smoothing in use: the user's value, or (0 = automatic) the bar-formula sigma of the line width. */
	public double effectiveZProfileSmoothing() {
		return zProfileSmoothing > 0 ? zProfileSmoothing : sigmaForWidth(lineWidth);
	}

	/** The clean-up rules as set here, for one detection run (RidgeCleanup keeps per-run state). */
	public RidgeCleanup cleanup() {
		RidgeCleanup rc = new RidgeCleanup();
		rc.kinkAngle = kinkRule ? kinkAngle : 0;
		rc.branch = branchRule;
		rc.crossing = crossingRule;
		rc.parallel = parallelRule;
		rc.turnRelease = turnRelease;
		rc.turnAngle = turnAngle;
		rc.turnTangent = turnTangent;
		rc.turnContinuity = turnContinuity;
		return rc;
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
		if (copyRule && copyRatio <= 1.0)
			return "ridge detection: the copy brightness ratio must be above 1 (got " + IJ.d2s(copyRatio, 2) + ")";
		if (kinkRule && (kinkAngle <= 0.0 || kinkAngle >= 180.0))
			return "ridge detection: the kink angle must lie between 0 and 180 degrees (got " + IJ.d2s(kinkAngle, 1) + ")";
		if (parallelRule && turnRelease && (turnAngle <= 0.0 || turnAngle >= 90.0 || turnTangent <= 0.0 || turnContinuity < 0.0 || turnContinuity > 1.0))
			return "ridge detection: turn release needs an angle between 0 and 90 degrees, a tangent above 0 line widths"
					+ " and a continuity between 0 and 1 (got " + IJ.d2s(turnAngle, 1) + ", " + IJ.d2s(turnTangent, 2) + ", " + IJ.d2s(turnContinuity, 2) + ")";
		if (zProfileSmoothing < 0.0)
			return "ridge detection: the z-profile smoothing must be 0 (automatic) or above (got " + IJ.d2s(zProfileSmoothing, 2) + ")";
		/*	New V0.2.1: only for derived thresholds. With the advanced values overridden, deriveFrom does not run, the contrast stays 0
		 *	and this check refused every such run on a channel with noise (MAD > 0) - Jan's own sperm settings among them. */
		if (!advancedOverride && madOfChannel > 0.0 && contrastHigh < 2.0 * madOfChannel)
			return "ridge detection: the line contrast this would look for (" + IJ.d2s(contrastHigh, 2)
					+ ") is below twice the channel noise level (" + IJ.d2s(2.0 * madOfChannel, 2)
					+ ") - raise 'contrast sensitivity', or the detector will trace noise";
		return null;
	}

	// ------------------------------------------------------------------ dialog

	/** The wiki page behind the dialogs' Help button. */
	public static final String HELP_URL = "https://github.com/hansenjn/CiliaQ/wiki/Ridge-detection-in-CiliaQ-Preparator";

	/**
	 * The ridge detection dialog. ImageJ's GenericDialog does not scroll, so the dialog is kept compact (two fields per
	 * row, the rule switches as one checkbox row, help in one line per section and behind the Help button); the advanced
	 * values are on a second dialog, opened only when "Show advanced settings" is ticked. Macro runs never show either:
	 * the Preparator reads macro options itself (readSettingsFromMacroString), so these labels are not macro keys.
	 */
	public static RidgeSettings initByGD(String task) throws Exception {
		final RidgeSettings inst = new RidgeSettings();
		final java.awt.Font heading = ciliaQ_Prep_jnh.CiliaQPrepMain.HEADING_FONT, sub = ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT,
				help = ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT;

		final GenericDialog gd = new GenericDialog(pluginName + ", version " + pluginVersion + " - ridge detection settings");
		gd.setInsets(0, 0, 0);
		gd.addMessage("Ridge detection (Steger line detector for filaments) - Settings for " + task, heading);

		gd.setInsets(0, 0, 0); gd.addMessage("----- Simple detector settings -----", sub);

		gd.setInsets(0, 5, 0); gd.addNumericField("Expected filament width in preprocessed image (px)", inst.lineWidth, 2);
		gd.setInsets(0, 5, 0); gd.addNumericField("Contrast sensitivity (higher = stricter = fewer lines)", inst.sensitivity, 2);
		gd.setInsets(0, 5, 0); gd.addNumericField("Min. filament length (px) - set just below shortest real filament", inst.minLength, 1);
		gd.setInsets(0, 5, 0); gd.addCheckbox("Resolve crossings - trace filaments through crossings instead of ending there", inst.resolveCrossings);
				
		gd.setInsets(0, 0, 0); gd.addMessage("----- Z-stacks -----", sub);
		
		gd.setInsets(0, 0, 0); gd.addChoice("Z method", Z_METHODS, inst.zMethod);
		gd.setInsets(0, 0, 0); gd.addMessage("Classic: 2D image or every plane traced separately; In-focus: detect each filament x,y point only where sharpest in z."
			    					+ "\n3D: keep filaments separate when overlapping in x,y but far in z (in-focus does not).", help);
		
		gd.setInsets(0, 0, 0); gd.addMessage("----- Clean-up detected ridges (separate, remove defocus copies, etc.) -----", sub);
		
		gd.setInsets(0, 5, 0); gd.addCheckbox("1 Copy rule - remove defocused artefacts (for in-focus / 3D)     --> brightness ratio", inst.copyRule);
		gd.addToSameRow(); gd.addNumericField("", inst.copyRatio, 1);
		
		gd.setInsets(0, 5, 0); gd.addCheckbox("2 Kink rule - cut a filament at sharp bend (two filaments connecting)     --> Min angle of kink (degrees)", inst.kinkRule);
		gd.addToSameRow(); gd.addNumericField("", inst.kinkAngle, 0);
		
		gd.setInsets(0,5,0); gd.addCheckbox("3 Branch rule - keep a filament ending on another's side separate", inst.branchRule);
		gd.setInsets(0,5,0); gd.addCheckbox("4 Crossing rule - keep filaments that cross separate", inst.crossingRule);
		gd.setInsets(0,5,0); gd.addCheckbox("5 Parallel rule - split filaments running closely side by side", inst.parallelRule);
		gd.setInsets(0,25,0); gd.addCheckbox("--> Turn release - keep a folded filament as one (in parallel rule)", inst.turnRelease);
		
		gd.setInsets(0,5,0); gd.addChoice("6 Short pieces (< 2 * fil. width & adjacent; e.g., forks at cilium tip)", SHORT_CHOICES, inst.shortPieces);
		
		gd.setInsets(0, 0, 0); gd.addMessage("----- Advanced -----", sub);
		gd.setInsets(0, 0, 0);
		gd.addCheckbox("Show advanced settings (sigma, thresholds, detector switches, z profile, turn release)", false);
		gd.addHelp(HELP_URL);
		gd.showDialog();
		if (gd.wasCanceled()) throw new Exception("Ridge detection settings dialog cancelled");

		// read back in the order the fields were added, per kind
		inst.lineWidth = gd.getNextNumber();
		inst.sensitivity = gd.getNextNumber();
		inst.minLength = gd.getNextNumber();
		inst.resolveCrossings = gd.getNextBoolean();

		inst.zMethod = gd.getNextChoice();

		inst.copyRule = gd.getNextBoolean();
		inst.copyRatio = gd.getNextNumber();
		
		inst.kinkRule = gd.getNextBoolean();
		inst.kinkAngle = gd.getNextNumber();
		
		inst.branchRule = gd.getNextBoolean();
		inst.crossingRule = gd.getNextBoolean();
		inst.parallelRule = gd.getNextBoolean();
		inst.turnRelease = gd.getNextBoolean();
		
		inst.shortPieces = gd.getNextChoice();

		boolean showAdvanced = gd.getNextBoolean();		
		
		if (showAdvanced) advancedByGD(inst);
		return inst;
	}

	/**
	 * The second dialog: the advanced values. Sigma and the thresholds are pre-filled with what the simple settings
	 * derive (on a 0-255 range, as a guide; processing derives them from each channel) and refresh when "Dark line"
	 * changes; a value edited away from that counts as an override. Cancel keeps the defaults and goes on.
	 */
	static void advancedByGD(final RidgeSettings inst) {
		final RidgeSettings preview = derivedPreview(inst.lineWidth, inst.sensitivity, inst.darkLine);
		final GenericDialog gd = new GenericDialog(pluginName + " - ridge detection: advanced settings");
		final java.awt.Font sub = ciliaQ_Prep_jnh.CiliaQPrepMain.SUB_HEADING_FONT, help = ciliaQ_Prep_jnh.CiliaQPrepMain.INSTRUCTIONS_FONT;
		gd.setInsets(0, 0, 0);
		gd.addMessage("--- Steger detector (derived from the simple settings; edit to override) ---", sub);
		gd.addNumericField("Sigma", preview.sigma, 3);
		final java.awt.TextField sigmaField = lastNumericField(gd);
		gd.addToSameRow();
		gd.addNumericField("Upper threshold", preview.upperThreshold, 3);
		final java.awt.TextField upperField = lastNumericField(gd);
		gd.addToSameRow();
		gd.addNumericField("Lower threshold", preview.lowerThreshold, 3);
		final java.awt.TextField lowerField = lastNumericField(gd);
		gd.addNumericField("Maximum filament length (px, 0 = unlimited)", inst.maxLength, 1);
		gd.setInsets(5, 20, 0);
		gd.addCheckboxGroup(1, 4, new String[] { "Dark line", "Correct position", "Estimate width", "Extend line" },
				new boolean[] { inst.darkLine, inst.correctPosition, inst.estimateWidth, inst.extendLine });
		final java.awt.Checkbox darkBox = gd.getCheckboxes() == null ? null : (java.awt.Checkbox) gd.getCheckboxes().get(0);
		gd.setInsets(10, 0, 0);
		gd.addMessage("--- In-focus and 3D ---", sub);
		gd.addNumericField("Z-profile smoothing (px, 0 = automatic)", inst.zProfileSmoothing, 2);
		gd.setInsets(-3, 20, 0);
		gd.addMessage("Smoothing of each point's intensity profile through z; automatic = the sigma of the width.", help);
		gd.setInsets(10, 0, 0);
		gd.addMessage("--- Turn release (parallel rule) ---", sub);
		gd.addNumericField("Angle (degrees)", inst.turnAngle, 0);
		gd.addToSameRow();
		gd.addNumericField("Tangent (line widths)", inst.turnTangent, 1);
		gd.addToSameRow();
		gd.addNumericField("Continuity (0-1)", inst.turnContinuity, 2);
		gd.addHelp(HELP_URL);
		/* "Dark line" changes the derived thresholds: refresh the three fields (by reference, so field positions do not matter) */
		gd.addDialogListener(new DialogListener() {
			boolean lastDark = inst.darkLine;
			public boolean dialogItemChanged(GenericDialog d, AWTEvent e) {
				boolean dark = darkBox != null && darkBox.getState();
				if (dark == lastDark) return true;
				lastDark = dark;
				RidgeSettings p = derivedPreview(inst.lineWidth, inst.sensitivity, dark);
				if (sigmaField != null) sigmaField.setText(IJ.d2s(p.sigma, 3));
				if (upperField != null) upperField.setText(IJ.d2s(p.upperThreshold, 3));
				if (lowerField != null) lowerField.setText(IJ.d2s(p.lowerThreshold, 3));
				return true;
			}
		});
		gd.showDialog();
		if (gd.wasCanceled()) return;	// keep the defaults

		double sigmaTyped = gd.getNextNumber(), upperTyped = gd.getNextNumber(), lowerTyped = gd.getNextNumber();
		inst.maxLength = gd.getNextNumber();
		inst.zProfileSmoothing = gd.getNextNumber();
		inst.turnAngle = gd.getNextNumber();
		inst.turnTangent = gd.getNextNumber();
		inst.turnContinuity = gd.getNextNumber();
		inst.darkLine = gd.getNextBoolean();
		inst.correctPosition = gd.getNextBoolean();
		inst.estimateWidth = gd.getNextBoolean();
		inst.extendLine = gd.getNextBoolean();

		/* An advanced value counts as an override only if it differs from what the simple settings derive, so a field that
		 * was only pre-filled is not mistaken for one. */
		RidgeSettings p = derivedPreview(inst.lineWidth, inst.sensitivity, inst.darkLine);
		if (Math.abs(sigmaTyped - p.sigma) > 1e-4 || Math.abs(upperTyped - p.upperThreshold) > 1e-4 || Math.abs(lowerTyped - p.lowerThreshold) > 1e-4) {
			inst.advancedOverride = true;
			inst.sigma = sigmaTyped;
			inst.upperThreshold = upperTyped;
			inst.lowerThreshold = lowerTyped;
		}
	}

	/** sigma and thresholds as the simple settings derive them on a 0-255 range (the dialogs' guide values) */
	static RidgeSettings derivedPreview(double lineWidth, double sensitivity, boolean darkLine) {
		RidgeSettings p = new RidgeSettings();
		p.lineWidth = lineWidth;
		p.sensitivity = sensitivity;
		p.darkLine = darkLine;
		p.deriveFrom(0.0, 255.0);
		return p;
	}

	/** the numeric field / checkbox just added to the dialog (null in a headless environment) */
	static java.awt.TextField lastNumericField(GenericDialog gd) {
		Vector<?> v = gd.getNumericFields();
		return v == null || v.isEmpty() ? null : (java.awt.TextField) v.lastElement();
	}
	static java.awt.Checkbox lastCheckbox(GenericDialog gd) {
		Vector<?> v = gd.getCheckboxes();
		return v == null || v.isEmpty() ? null : (java.awt.Checkbox) v.lastElement();
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
		sb.append("		Z method	").append(zMethod).append("\n");
		sb.append("		Copy rule	").append(copyRule).append("\n");
		sb.append("		Copy brightness ratio	").append(IJ.d2s(copyRatio, 2)).append("\n");
		sb.append("		Kink rule	").append(kinkRule).append("\n");
		sb.append("		Kink angle (degrees)	").append(IJ.d2s(kinkAngle, 1)).append("\n");
		sb.append("		Branch rule	").append(branchRule).append("\n");
		sb.append("		Short pieces	").append(shortPieces).append("\n");
		sb.append("		Crossing rule	").append(crossingRule).append("\n");
		sb.append("		Parallel rule	").append(parallelRule).append("\n");
		sb.append("		Turn release	").append(turnRelease).append("\n");
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
		sb.append("		Z-profile smoothing (px, 0 = automatic)	").append(IJ.d2s(zProfileSmoothing, 3)).append("\n");
		if (zProfileSmoothing <= 0)	// information only, not read back: the value the automatic setting used
			sb.append("		Z-profile smoothing in use (px)	").append(IJ.d2s(effectiveZProfileSmoothing(), 3)).append("\n");
		sb.append("		Turn release angle (degrees)	").append(IJ.d2s(turnAngle, 1)).append("\n");
		sb.append("		Turn release tangent (line widths)	").append(IJ.d2s(turnTangent, 2)).append("\n");
		sb.append("		Turn release continuity	").append(IJ.d2s(turnContinuity, 3)).append("\n");
		return sb.toString();
	}
}
