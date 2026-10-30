package ciliaQ_Prep_jnh.ridge_detection;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;

import ciliaQ_Prep_jnh.ProgressReporter;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.Line;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.LineDetector;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.Lines;
import ciliaQ_Prep_jnh.ij_ridge_detector_steger.OverlapOption;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.CompositeImage;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;
import ij.process.ShortProcessor;

/**
 * CiliaQ Preparator - RIDGE DETECTION segmentation (Steger line detector), producing a LABEL image.
 *
 * Detects filamentous structures and writes one integer label per object into the output channel, so
 * that touching filaments can later be told apart (0 = background, 1..N = objects). All of the detection,
 * z-linking and label bookkeeping lives here; CiliaQPrepMain only calls detect(...) and then writes the
 * result, keeping the change to the main plugin small.
 *
 * HOW IT WORKS.
 *  1. DETECTION IS 2D, PER PLANE. The Steger detector operates on a single image, so each z-slice is
 *     detected independently. The Gaussian sigma and the upper/lower thresholds are derived ONCE from
 *     stack-wide statistics (in RidgeSettings) and reused for every plane - deriving them per slice
 *     makes the line count swing wildly from plane to plane, which is threshold inconsistency rather
 *     than real structure and makes z-linking impossible.
 *  2. Z-LINKING BY PIXEL OVERLAP. Lines in adjacent planes that share any pixel are merged with a
 *     union-find, so a filament running through several slices becomes one object. The overlap is NOT
 *     dilated: dilation raises the overlap rate and worsens the known over-fusion.
 *  3. OVER-FUSION AT CROSSINGS IS ACCEPTED. Where filaments cross they share pixels, so overlap-linking
 *     fuses them into one object. Separating crossings by intensity is not possible, and de-cluttering
 *     is deliberately left to CiliaQ, which owns the skeleton machinery.
 *  4. CONSECUTIVE LABELS. Final labels are consecutive positive integers from 1, numbered from the
 *     voxels actually painted (not from line indices - a line fully covered by an earlier line keeps an
 *     id but no pixels). Labels wrap at 2^24, above which float32 can no longer hold consecutive
 *     integers exactly; a wrap is reported rather than silently producing duplicate ids.
 *
 * SAFETY CAP. A plane that yields far more lines than pixels is not detecting filaments but tracing
 * noise, and can exhaust the heap. LINE_CAP_DIVISOR bounds the number of lines per plane and aborts the
 * channel with an explanatory message, anchored on that failure mode rather than on a noise statistic
 * (which is zero on background-free images and so would never fire when needed).
 *
 * OUTPUT BIT DEPTH. Detection writes into a scratch array first; only once the object count is known
 * does the caller promote the output image to the smallest bit depth that holds the labels exactly (see
 * requiredBitDepth and promoteKeepingValues). A Glasbey LUT is applied for legibility (applyGlasbeyLut).
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
public class RidgeSegmenter {

	/** float32 represents consecutive integers exactly only up to 2^24. */
	public static final int MAX_LABEL = 16777216;

	/**
	 * Hard safety cap on detected lines per plane, as a fraction of the pixel count.
	 *
	 * The MAD-based guard in RidgeSettings.rejectionReason() is not enough: MAD is 0 on background-free
	 * data, so that noise-anchored guard never fires exactly where it is needed, and the detector can
	 * produce enough Line objects to exhaust the heap.
	 *
	 * This cap is anchored on the failure mode itself rather than on a noise proxy: a plane that yields
	 * more lines than one per LINE_CAP_DIVISOR pixels is not detecting filaments.
	 */
	public static final int LINE_CAP_DIVISOR = 40;

	/** Result of a detection pass: per-plane label arrays plus how many distinct objects were found. */
	public static class Result {
		public final int[][] labels;	// [slice][y*width + x], 0 = background
		public final int nObjects;
		public final int nLinesTotal;
		public final int width, height, nSlices;
		public final boolean wrapped;
		Result(int[][] labels, int nObjects, int nLinesTotal, int w, int h, int d, boolean wrapped) {
			this.labels = labels; this.nObjects = nObjects; this.nLinesTotal = nLinesTotal;
			this.width = w; this.height = h; this.nSlices = d; this.wrapped = wrapped;
		}
	}

	// ------------------------------------------------------------------ detection

	/**
	 * Detect filaments in every plane of a single-channel, single-timepoint image and link them in z.
	 * Writes nothing: the caller decides the output bit depth once nObjects is known, which is why this
	 * returns a scratch array (the pattern the rest of the Preparator uses).
	 */
	public static Result detect(ImagePlus channelImp, RidgeSettings rs,  ProgressReporter progress) throws IllegalStateException {
		final int W = channelImp.getWidth(), H = channelImp.getHeight(), D = channelImp.getStackSize();
		final OverlapOption overlap = rs.resolveCrossings ? OverlapOption.SLOPE : OverlapOption.NONE;

		int[][] perPlane = new int[D][];      // per slice: the label array [y*W + x], one entry per plane (filled in the loop)
		int[] perPlaneCount = new int[D];     // per slice: how many lines were detected and rasterised in that plane
		int[] offset = new int[D + 1];        // running start index per slice into the global line numbering (prefix sums of perPlaneCount; offset[D] = total lines)

		// One warning per channel is enough; avoids spam if several planes are slow.
		final boolean[] warned = { false };
		// 45 s for a single plane is far beyond normal; a correctly-thresholded 2048x2048 plane is seconds.
		final SlowWatchdog watchdog = new SlowWatchdog(45_000, () -> {
			if(warned[0]) return;
			warned[0] = true;
			progress.notifyMessage("Ridge detection is taking unusually long on this image. This usually"
					+ " means the thresholds are too permissive and the detector is tracing noise rather"
					+ " than filaments. You can let it finish, but if it does not complete soon, cancel and"
					+ " raise 'contrast sensitivity'. (This message appears once and does not stop"
					+ " processing.)", ProgressReporter.NOTIFICATION);
		});
		
		for (int z = 0; z < D; z++) {
			progress.updateBarText("Ridge detection slice " + z);			
			ImageProcessor ip = channelImp.getStack().getProcessor(z + 1);
			int[] lab = new int[W * H];
			int nLines = 0;
			try {
				LineDetector det = new LineDetector();
				watchdog.arm();						// start the clock for THIS plane
				Lines lines;
				try {
					lines = det.detectLines(ip, rs.sigma, rs.upperThreshold, rs.lowerThreshold,
							rs.minLength, rs.maxLength, rs.darkLine, rs.correctPosition,
							rs.estimateWidth, rs.extendLine, overlap);
				} finally {
					watchdog.disarm();				// plane finished (or threw) - stop the clock
				}
				int cap = Math.max(2000, (W * H) / LINE_CAP_DIVISOR);
				if (lines.size() > cap) {
					throw new IllegalStateException("ridge detection produced " + lines.size()
							+ " lines on plane " + (z + 1) + ", above the safety cap of " + cap
							+ " (one per " + LINE_CAP_DIVISOR + " pixels). The thresholds are too"
							+ " permissive for this image - raise 'contrast sensitivity'.");
				}
				for (Line l : lines) {
					nLines++;
					rasterise(l, lab, W, H, nLines, rs.estimateWidth);
				}
			} catch (IllegalStateException overCap) {
	 			/* The safety cap is fatal for the whole channel, not a per-plane hiccup: continuing
	 			 * would hit the same wall on the next plane, or exhaust the heap before it got there. */
	 			throw overCap;
			} catch (Throwable e) {
				StringBuilder trace = new StringBuilder();
				for (StackTraceElement line : e.getStackTrace()) {
					trace.append(" \n ").append(line.toString());
				}
				// Any other failure on one plane must not lose the others.
				progress.notifyMessage("CiliaQ Preparator: ridge detection failed on plane "
				            + (z + 1) + " - " + trace, ProgressReporter.NOTIFICATION);
			}
			perPlane[z] = lab;
			perPlaneCount[z] = nLines;
		}
		offset[0] = 0;
		for (int z = 0; z < D; z++) offset[z + 1] = offset[z] + perPlaneCount[z];
		final int totalLines = offset[D];

		/*
		 * ----------------------------------------------------------------------------
		 * 
		 * z-link by pixel overlap, union-find. Known consequence, accepted for v0.2.1: 
		 * two lines in plane z that both overlap one line in plane z+1 are merged, and
		 * transitivity propagates that through the stack, so crossing filaments fuse.
		 * De-cluttering is planned to happen in CiliaQ.
		 */
						
		// union-find "parent" array, indexed by GLOBAL line id (offset[z] + localId).
		// +1 because global ids run 1..totalLines (id 0 is never used - 0 means background).
		int[] parent = new int[totalLines + 1];

		/* Start every line as its own group: parent[i] = i means "i is its own root".
		 * (So initially there are totalLines separate groups, none merged yet.) */
		for (int i = 0; i <= totalLines; i++) {
			parent[i] = i;
		}

		/* Compare each plane with the one directly above it (z and z+1).
		 * "z + 1 < D" stops at the second-to-last plane, since the last has no plane above it. */
		for (int z = 0; z + 1 < D; z++) {

			/* Remember which (lineInZ, lineInZ+1) pairs we've already merged for THIS plane pair,
			 * so we call union() once per distinct overlapping pair instead of once per shared pixel
			 * (two lines can overlap over many pixels; we only need to merge them once). */
			HashSet<Long> seenPair = new HashSet<Long>();

			/* Walk every pixel position (same index i means same x,y in both planes,
			 * because both label arrays are laid out as y*W + x with identical W,H). */
			for (int i = 0; i < W * H; i++) {
				// a = local line id at this pixel in plane z; b = local line id at same pixel in plane z+1.
				int a = perPlane[z][i], b = perPlane[z + 1][i];

				/* 0 means "no line here" (background). If either plane has no line at this pixel,
				 * there is no overlap to record - skip. */
				if (a == 0 || b == 0) continue;
				
				/* Pack the pair (a, b) into a single 64-bit value so it can be a HashSet key: 
				 *   high 32 bits = a, low 32 bits = b.
				 * "(long) a << 32" shifts a into the upper half; "b & 0xffffffffL" keeps b's lower 32 bits
				 * (the mask stops a negative-looking int from sign-extending into the upper half). */
				long key = (((long) a) << 32) | (b & 0xffffffffL);

				// add(key) returns false if this exact (a,b) pair was already recorded - in that case we can skip.
				if (!seenPair.add(key)) continue;

				/* First time we see this overlapping pair: merge the two lines into one group.
				 * offset[z] + a and offset[z+1] + b convert the local ids into GLOBAL ids before merging. */
				union(parent, offset[z] + a, offset[z + 1] + b);
			}
		}

		/*
		 * Turn union-find groups into the final object labels.
		 *
		 * After the z-linking above, every line belongs to a group identified by its union-find ROOT
		 * (an arbitrary global line id, not a tidy 1,2,3,...). Here we map each distinct root to a
		 * consecutive label starting at 1, so the output image carries clean ids 1..N with 0 = background.
		 *
		 * IMPORTANT: we iterate over the voxels actually PAINTED in the image, not over the list of line
		 * indices. rasterise() claims a voxel only if it is still free, so a line whose footprint is
		 * entirely covered by an earlier line keeps an id but paints NO pixels. If we numbered from the line
		 * list we would hand out labels that never appear in the image (and nObjects would overstate what is
		 * really there). Numbering from painted voxels guarantees the contract: labels are consecutive, and
		 * nObjects equals the number of distinct objects actually present in the output.
		 */
		HashMap<Integer, Integer> idOf = new HashMap<Integer, Integer>();	// Maps a union-find root -> the final consecutive label assigned to that group.
		
		boolean wrapped = false; // set true if we run out of exactly-representable label values (see MAX_LABEL)
		
		// --- pass 1: discover every group that actually paints pixels, and assign it a label ---
		for (int z = 0; z < D; z++) {
			for (int i = 0; i < W * H; i++) {
				int l = perPlane[z][i];			// this voxel's local (per-plane) line id; 0 = background
				if (l == 0) continue;			// skip background voxels
				int root = find(parent, offset[z] + l);	// the group this line belongs to (global id -> its root)
				if (!idOf.containsKey(root)) {		// first voxel we've seen from this group
					int next = idOf.size() + 1;	// labels are handed out in order of first appearance: 1, 2, 3, ...
					if (next > MAX_LABEL) {		// ran past what float32 can hold as an exact integer:
						wrapped = true;		// flag it so the caller can warn the user,
						next = ((idOf.size()) % MAX_LABEL) + 1;	// and reuse a low label (duplicate ids - accepted, not fatal)
					}
					idOf.put(root, next);		// remember: this group -> this label
				}
			}
		}
		
		// --- pass 2: write the assigned label into every painted voxel ---
		int[][] out = new int[D][W * H];		// the final label image, [slice][y*W + x], 0 = background
		for (int z = 0; z < D; z++) {
			for (int i = 0; i < W * H; i++) {
				int l = perPlane[z][i];
				if (l == 0) continue;			// background stays 0
				
				// look up this voxel's group (root) and write its consecutive label
				out[z][i] = idOf.get(find(parent, offset[z] + l)).intValue();
			}
		}
		
		// idOf.size() is the number of DISTINCT groups that painted at least one voxel = the true object count.
		return new Result(out, idOf.size(), totalLines, W, H, D, wrapped);
	}

	/**
	 * Paint one detected line into the per-plane label array.
	 *
	 * The Steger detector returns a line as a sequence of sub-pixel points along its centre. This stamps a
	 * filled DISC at each point, so the whole line is drawn with thickness: the disc radius is half the
	 * line's locally estimated width (when estimateWidth is on), giving a thin line where the structure is
	 * thin and a wider one where it is wide. If width estimation is off, or unavailable at a point, a
	 * minimal radius is used so the line is still at least one pixel thick.
	 *
	 * FIRST WRITER WINS: a voxel is claimed only if it is still background (0). Earlier lines therefore keep
	 * their pixels where lines overlap, rather than being overwritten. One consequence is that a line whose
	 * footprint is entirely covered by earlier lines paints nothing - which is why object numbering later
	 * counts PAINTED voxels, not line indices (see the renumbering step in detect()).
	 *
	 * @param l             the detected line, providing its centre points and (optionally) per-point widths
	 * @param lab           the plane's label array [y*W + x] to paint into; 0 = background
	 * @param W, H          plane dimensions, for bounds checking and indexing
	 * @param id            the local (per-plane) label to write for this line
	 * @param estimateWidth true to use the line's estimated width for the disc radius; false for a thin line
	 */
	private static void rasterise(Line l, int[] lab, int W, int H, int id, boolean estimateWidth) {
		float[] xs = l.getXCoordinates(), ys = l.getYCoordinates();
		float[] wl = estimateWidth ? l.getLineWidthL() : null;
		float[] wr = estimateWidth ? l.getLineWidthR() : null;
		for (int i = 0; i < xs.length; i++) {
			double halfWidth = (wl != null && wr != null && i < wl.length && i < wr.length)
					? Math.max(0.5, (wl[i] + wr[i]) / 2.0) : 1.0;
			int r = (int) Math.ceil(halfWidth);
			for (int dy = -r; dy <= r; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					if (dx * dx + dy * dy > halfWidth * halfWidth) continue;
					int x = (int) Math.round(xs[i]) + dx, y = (int) Math.round(ys[i]) + dy;
					if (x < 0 || x >= W || y < 0 || y >= H) continue;
					if (lab[y * W + x] == 0) lab[y * W + x] = id;
				}
			}
		}
	}

	/**
	 * Union-find "find": return the ROOT of the group that element a belongs to, by following parent
	 * pointers up until one points to itself. Flattens the path on the way (p[a] = p[p[a]]) so later
	 * lookups on the same branch are faster.
	 */
	private static int find(int[] p, int a) {
		while (p[a] != a) {
			p[a] = p[p[a]];
			a = p[a];
		}
		return a;
	}

	/**
	 * Union-find "union": merge the groups containing a and b into one. Finds both roots and, if they
	 * differ, points one root at the other so from now on both elements resolve to the same root.
	 */
	private static void union(int[] p, int a, int b) {
		a = find(p, a); b = find(p, b);
		if (a != b) p[b] = a;
	}

	// ------------------------------------------------------------------ bit depth

	/** Smallest bit depth that can hold labels 0..nObjects as exact integers. */
	public static int requiredBitDepth(int nObjects) {
		if (nObjects <= 255) return 8;
		if (nObjects <= 65535) return 16;
		return 32;
	}

	/**
	 * Promote an image to a deeper bit depth KEEPING EVERY VALUE IDENTICAL.
	 *
	 * Deliberately does NOT use ij.process.ImageConverter: its behaviour depends on the static global
	 * ImageConverter.setDoScaling(boolean), which any other plugin in the session may have flipped, and
	 * scaling would destroy label values and alter intensity channels. Copying voxel by voxel is exact
	 * for 8 -> 16 and for 8/16 -> 32 (integers up to 2^24 are exact in float32).
	 *
	 * @return a new ImagePlus with the same dimensions, calibration and values; or the input unchanged
	 *         if it is already deep enough.
	 */
	public static ImagePlus promoteKeepingValues(ImagePlus imp, int targetBitDepth) {
		if (imp.getBitDepth() >= targetBitDepth) {
			// already deep enough - nothing to do
			return imp;
		}
		
		final int W = imp.getWidth(), H = imp.getHeight();
		final int nC = imp.getNChannels(), nZ = imp.getNSlices(), nT = imp.getNFrames();
		ImageStack src = imp.getStack();
		ImageStack dst = new ImageStack(W, H);	// the promoted stack we build up
		for (int i = 1; i <= src.getSize(); i++) {
			// copy every slice of the (flat) stack
			ImageProcessor sp = src.getProcessor(i);
			
			// a deeper processor of the target type; 16-bit -> ShortProcessor, 32-bit -> FloatProcessor
			ImageProcessor dp = (targetBitDepth == 16) ? new ShortProcessor(W, H)
					: new FloatProcessor(W, H);
			for (int k = 0; k < W * H; k++) dp.setf(k, sp.getf(k));
			dst.addSlice(src.getSliceLabel(i), dp);	// keep the original slice label
		}
		ImagePlus out = new ImagePlus(imp.getTitle(), dst);
		out.setCalibration(imp.getCalibration());
		out.setDimensions(nC, nZ, nT);
		if (nC > 1) out.setOpenAsHyperStack(true);	// a multi-channel stack must be a hyperstack

		/*
		 * Carry over composite mode and the per-channel LUTs. Without this the promoted image comes back
		 * with composite = false and every channel LUT lost, so a Glasbey LUT applied to an earlier
		 * channel is wiped as soon as a later channel triggers promotion. (Pixel values are preserved
		 * regardless; it is only the display state that would otherwise be thrown away.)
		 */
		if (imp.isComposite() && nC > 1) {
			CompositeImage src2 = (CompositeImage) imp;
			CompositeImage co = new CompositeImage(out, src2.getMode());	// same composite display mode
			for (int c = 1; c <= nC; c++) {										// copy each channel's lookup table across
				LUT l = src2.getChannelLut(c);
				if (l != null) co.setChannelLut(l, c);
			}
			co.setCalibration(imp.getCalibration());
			return co;
		}
		return out;
	}

	// ------------------------------------------------------------------ LUT

	/**
	 * Apply a Glasbey LUT to one channel so a label image is readable at a glance. A Glasbey LUT gives
	 * neighbouring label values very different colours, so adjacent objects are easy to tell apart.
	 * glasbey_on_dark keeps index 0 black, which is what background should look like.
	 * Silently does nothing if the LUT file is not present, since this is cosmetic.
	 */
	public static void applyGlasbeyLut(ImagePlus imp, int channel) {
		try {
			// preferred first; fall back to other Glasbey variants if the dark one is not installed
			String[] candidates = { "glasbey_on_dark.lut", "glasbey_inverted.lut", "glasbey.lut"};
			File found = null;
			for (int i = 0; i < candidates.length && found == null; i++) {
				// stop at the first that exists
				File f = new File(IJ.getDirectory("luts") + candidates[i]);
				if (f.exists()) found = f;
			}
			
			if (found == null) return;	// no Glasbey LUT installed - leave the display as is
			
			LUT lut = openLut(found);
			if (lut == null) return;	// unreadable file - skip, cosmetic only
			
			if (imp.isComposite()) {
				// multi-channel: set the LUT on this channel alone, leaving the others untouched
				((CompositeImage) imp).setChannelLut(lut, channel);
			} else {
				// single-channel: the LUT applies to the whole image
				imp.getProcessor().setColorModel(lut);
			}
		} catch (Throwable e) {
			// Never let a missing or malformed LUT stop a segmentation run
		}
	}

	/**
	 * Read a 768-byte raw or ImageJ .lut file into a LUT.
	 *
	 * A LUT is 256 colours = three 256-byte tables (red, green, blue) laid out consecutively, 768 bytes
	 * total. ImageJ .lut files may prepend a header, so the 768-byte table is taken from the END of the
	 * file. Returns null on any problem - the caller treats a missing/unreadable LUT as cosmetic.
	 */
	private static LUT openLut(File f) {
		try {
			long len = f.length();
			if (len < 1) return null;
			byte[] all = new byte[(int) len];
			java.io.FileInputStream in = new java.io.FileInputStream(f);
			try {
				// read may return fewer bytes per call, so loop until the buffer is full
				int read = 0;
				while (read < all.length) {
					int n = in.read(all, read, all.length - read);
					if (n < 0) return null;		// stream ended early - unreadable
					read += n;
				}
			} finally { in.close(); }

			byte[] r = new byte[256], g = new byte[256], b = new byte[256];

			/*
			 * ImageJ .lut files come in two formats:
			 *   - BINARY: 768 raw bytes (256 R, 256 G, 256 B), optionally after a header.
			 *   - ASCII : 256 text lines, each "R G B" as decimal numbers (possibly with an index column).
			 * Many Fiji LUTs (including glasbey_on_dark.lut) are ASCII, which the old binary-only reader
			 * misparsed into garbage colours. Try ASCII first (detect by presence of digits + whitespace
			 * and no high-bit bytes), else fall back to the binary layout.
			 */
			if (looksLikeAscii(all)) {
				if (!parseAsciiLut(new String(all, "US-ASCII"), r, g, b)) return null;
			} else {
				if (len < 768) return null;
				int off = (int) (len - 768);	// header, if any, sits before the 3x256 table
				System.arraycopy(all, off, r, 0, 256);
				System.arraycopy(all, off + 256, g, 0, 256);
				System.arraycopy(all, off + 512, b, 0, 256);
			}

			// Force index 0 (background) to black: labels start at 1, so index 0 is always background.
			r[0] = 0; g[0] = 0; b[0] = 0;

			return new LUT(r, g, b);
		} catch (Throwable e) {
			return null;
		}
	}

	/** Heuristic: a LUT is ASCII if its bytes are all printable/whitespace (no raw high-bit values). */
	private static boolean looksLikeAscii(byte[] data) {
		int sample = Math.min(data.length, 2048);	// first 2 KB is plenty to decide
		boolean sawDigit = false;
		for (int i = 0; i < sample; i++) {
			int c = data[i] & 0xff;
			if (c >= '0' && c <= '9') { sawDigit = true; continue; }
			// allow space, tab, CR, LF, and a few separators; anything else (esp. >126) means binary
			if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ',' || c == '.') continue;
			return false;	// a non-text byte -> treat as binary
		}
		return sawDigit;
	}

	/**
	 * Parse an ASCII LUT: 256 lines of whitespace-separated integers. Each line is either "R G B" or
	 * "index R G B"; in the 4-column case the first column (the index) is dropped. Returns false if fewer
	 * than 256 colour rows are found.
	 */
	private static boolean parseAsciiLut(String text, byte[] r, byte[] g, byte[] b) {
		String[] lines = text.split("\\r?\\n");
		int row = 0;
		for (int i = 0; i < lines.length && row < 256; i++) {
			String line = lines[i].trim();
			if (line.isEmpty()) continue;
			String[] tok = line.split("[\\s,]+");
			int rc, gc, bc;
			if (tok.length >= 4) {			// "index R G B" -> skip the index column
				rc = Integer.parseInt(tok[1]);
				gc = Integer.parseInt(tok[2]);
				bc = Integer.parseInt(tok[3]);
			} else if (tok.length == 3) {	// "R G B"
				rc = Integer.parseInt(tok[0]);
				gc = Integer.parseInt(tok[1]);
				bc = Integer.parseInt(tok[2]);
			} else {
				continue;					// not a colour row (header/comment) - skip
			}
			r[row] = (byte) (rc & 0xff);
			g[row] = (byte) (gc & 0xff);
			b[row] = (byte) (bc & 0xff);
			row++;
		}
		return row >= 256;
	}

	// ------------------------------------------------------------------ writing

	/**
	 * Write one timepoint's labels into the output image.
	 *
	 * Copies the per-slice label arrays from a detection Result into the given channel/frame of the
	 * output hyperstack. The output image must already be deep enough to hold the label values exactly -
	 * promote it with promoteKeepingValues before calling this, or large labels will be truncated.
	 *
	 * @param res             the detection result, holding labels[slice][y*width + x]
	 * @param writeImp        the output image to write into (already at a sufficient bit depth)
	 * @param channelWriteImp the 1-based channel in writeImp to write the labels to
	 * @param frame           the 0-based timepoint; converted to 1-based for ImageJ's stack index
	 */
	public static void writeLabels(Result res, ImagePlus writeImp, int channelWriteImp, int frame) {
		for (int s = 0; s < res.nSlices; s++) {
			/* ImageJ stack indices are 1-based for channel/slice/frame; getStackIndex returns 1-based,
			 * so subtract 1 to get the 0-based index that setVoxel expects for the z/stack position */
			int idx = writeImp.getStackIndex(channelWriteImp, s + 1, frame + 1) - 1;
			for (int x = 0; x < res.width; x++) {
				for (int y = 0; y < res.height; y++) {
					// res.labels is laid out as y*width + x; write it to voxel (x, y, idx)
					writeImp.getStack().setVoxel(x, y, idx, res.labels[s][y * res.width + x]);
				}
			}
		}
	}

	/**
	 * Runs {@code onSlow} once if {@code armMillis} elapse before disarm() is called. Used to warn, without
	 * blocking or interrupting, when a single detectLines() call is taking unusually long - the symptom of
	 * thresholds so permissive that the detector is tracing noise. Never stops the work it watches.
	 */
	static final class SlowWatchdog {
		private final javax.swing.Timer timer;		// Swing timer fires on the EDT, fine for a message
		SlowWatchdog(int armMillis, Runnable onSlow){
			timer = new javax.swing.Timer(armMillis, e -> onSlow.run());
			timer.setRepeats(false);
		}
		void arm(){ timer.restart(); }
		void disarm(){ timer.stop(); }
	}
	
	/** Utility class - static methods only; never instantiated. */
	private RidgeSegmenter() { }
}
