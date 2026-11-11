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

	/**
	 * New V0.2.1: the ERROR and NOTE messages of one task (1 = the first image), oldest first, each as
	 * "ERROR<tab>message" or "WARNING<tab>message", so the plugin can write them into that image's report (_CQP.txt).
	 * Each implementation stores next to every message the task during which it came. LOG messages are left out.
	 */
	java.util.List<String> getNotes(int task);

	/**
	 * New V0.2.1: the prefix "Task <task>/<tasks>" for a message that has none, so the messages of a batch can be told
	 * apart; "Channel <c>: ..." becomes "Task <task>/<tasks>, channel <c>: ...".
	 */
	static String withTask(String message, int task, int tasks) {
		if (message == null || message.startsWith("Task ")) return message;
		String t = "Task " + task + "/" + tasks;
		if (message.startsWith("Channel ")) return t + ", channel " + message.substring("Channel ".length());
		return t + ": " + message;
	}

	/** Allows to Opt-out for the console implementation. */
	void setVisible(boolean visible);

	/** For the console implementation. */
	void setLocation(int x, int y);
}