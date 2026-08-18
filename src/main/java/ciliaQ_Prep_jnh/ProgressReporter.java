package ciliaQ_Prep_jnh;

/**
 * Everything CiliaQ Preparator needs from a progress reporter.
 *
 * Exists so the plugin can run under --headless, where a javax.swing.JFrame cannot even be
 * constructed. ProgressDialog implements this for interactive use; ConsoleProgress
 * implements it for headless and macro use.
 * 
 * Added in August 2026 for version v0.2.0 to implement headless / console-based running of CiliaQ Preparator.
 * 
 * Copyright (C) @author Jan Niklas Hansen.
 *  */
public interface ProgressReporter {

	static final int ERROR = 0, NOTIFICATION = 1, LOG = 2;

	void updateBarText(String text);
	void replaceBarText(String text);
	void setBar(double fractionOfTask);
	void addToBar(double addFractionOfTask);
	void moveTask(int task);
	void notifyMessage(String message, int type);

	/** Allows to Opt-out for the console implementation. */
	void setVisible(boolean visible);

	/** For the console implementation. */
	void setLocation(int x, int y);
}