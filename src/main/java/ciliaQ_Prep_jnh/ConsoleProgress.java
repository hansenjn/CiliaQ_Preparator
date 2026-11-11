package ciliaQ_Prep_jnh;

import ij.IJ;

/**
 * Progress reporting for headless and macro runs: everything goes to the ImageJ log, which
 * --headless --console prints.
 * 
 * Added in August 2026 for version v0.2.0 to implement console-based running of CiliaQ Preparator.
 * 
 * Copyright (C) @author Jan Niklas Hansen.
 */
public class ConsoleProgress implements ProgressReporter {

	public boolean notificationsAvailable = false, errorsAvailable = false;
	private final String [] taskList;
	private final int tasks;
	private int task = 1;
	private String lastText = "";
	private final java.util.ArrayList<String> notes = new java.util.ArrayList<String>();		// New V0.2.1: "ERROR/WARNING<tab>message"
	private final java.util.ArrayList<Integer> noteTasks = new java.util.ArrayList<Integer>();	// New V0.2.1: the task of each note

	public ConsoleProgress(String [] taskList, int tasks) {
		this.taskList = taskList == null ? new String [0] : taskList.clone();
		this.tasks = tasks;
		IJ.log("CiliaQ Preparator: " + tasks + " task(s) queued.");
	}

	public void updateBarText(String text) {
		// Suppress repeats: the plugin updates the bar text inside per-voxel loops.
		if (text == null || text.equals(lastText)) return;
		lastText = text;
		IJ.log("  [" + task + "/" + tasks + "] " + text);
	}

	public void replaceBarText(String text) {
		updateBarText(text);
	}

	public void setBar(double fractionOfTask) { /* no bar to draw */ }

	public void addToBar(double addFractionOfTask) { /* no bar to draw */ }

	public void moveTask(int task) {
		String name = (task >= 0 && task < taskList.length) ? taskList[task] : "";
		IJ.log("  finished task " + (task + 1) + "/" + tasks + (name.isEmpty() ? "" : ": " + name));
		this.task = Math.min(task + 2, tasks);
	}

	public synchronized void notifyMessage(String message, int type) {	// V0.2.1: synchronized, see getNotes
		message = ProgressReporter.withTask(message, task, tasks);		// New V0.2.1: every message names its task
		if (type == ERROR) {
			errorsAvailable = true;
			IJ.log("ERROR: " + message);
		} else if (type == NOTIFICATION) {
			notificationsAvailable = true;
			IJ.log("NOTE: " + message);
		} else {
			IJ.log(message);
		}
		if (type != LOG) {	// New V0.2.1: kept for the report, see getNotes
			notes.add((type == ERROR ? "ERROR" : "WARNING") + "	" + message);
			noteTasks.add(task);
		}
	}
	
	/** New V0.2.1: see ProgressReporter.getNotes. synchronized: the ridge detection's watchdog reports from its own thread. */
	public synchronized java.util.List<String> getNotes(int task) {
		java.util.ArrayList<String> out = new java.util.ArrayList<String>();
		for (int i = 0; i < notes.size(); i++) {
			if (noteTasks.get(i) == task) out.add(notes.get(i));
		}
		return out;
	}

	public void setVisible(boolean visible) { /* nothing to show */ }

	public void setLocation(int x, int y) { /* nothing to place */ }
}