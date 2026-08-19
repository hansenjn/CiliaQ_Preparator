package ciliaQ_Prep_jnh;

/**
 * Accumulates the metadata text without touching AWT.
 *
 * Replaces ij.text.TextPanel, which extends java.awt.Panel and constructs Scrollbars in its
 * constructor - that throws HeadlessException under --headless. Since V0.2.0 writes the
 * metadata with writeTextFile(path, text) instead of TextPanel.saveAs(), the panel was only
 * ever being used as a StringBuilder with a GUI attached.
 *
 * Line semantics match TextPanel.getText() exactly: every append() contributes one line
 * terminated by '\n', empty appends produce empty lines, and trailing whitespace is preserved
 * verbatim. Both matter - the metadata format encodes a disabled option as an EMPTY LINE, and
 * the "Preparation settings:" line legitimately ends with a tab.
 *
 * Added in version V0.2.0.
 *
 * Copyright (C) 2019-2026 Jan Niklas Hansen.
 */
public class TextCollector {

	private final StringBuilder sb = new StringBuilder();

	/**
	 * @param title kept only for signature compatibility with TextPanel; unused.
	 */
	public TextCollector(String title) {
	}

	/** Append one line. A null argument is treated as an empty line, as TextPanel does. */
	public void append(String line) {
		sb.append(line == null ? "" : line);
		sb.append('\n');
	}

	/** @return the whole text, every line terminated by '\n'. */
	public String getText() {
		return sb.toString();
	}
}