package ciliaQ_Prep_jnh;

/**
 * This class wraps the functionality to select multiple files from the system
 * by a gui. It extends the OpenFilesDialog ("Multi-Taks-Manager") by
 * adding an function to select files containing certain pattern (regex or
 * non-regex) in all subdirectories of a chosen root directory.
 * 
 * Parts of the code were inherited from MotiQ (https://github.com/hansenjn/MotiQ).
 * 
 * @author Jan Niklas Hansen and Sebastian Rassmann *
 */

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.LinkedList;
import java.util.Stack;

import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;

import ij.gui.GenericDialog;

@SuppressWarnings("serial") // JFrame is Serializable, but this dialog is never saved or sent, so no serialVersionUID is needed
public class OpenFilesDialog extends javax.swing.JFrame implements ActionListener {
	LinkedList<File> filesToOpen = new LinkedList<File>();
	volatile boolean done = false;	// New in V0.2.1: volatile, read by the plugin's thread while the window sets it
	boolean dirsaved = false;
	File saved;// = new File(getClass().getResource(".").getFile());
	JMenuBar jMenuBar1;
	JMenu jMenu3, jMenu5;
	JSeparator jSeparator2;
	JPanel bgPanel;
	JScrollPane jScrollPane1;
	JList<String> Liste1;
	JButton loadSingleFilesButton, loadByPatternButtom, removeFileButton, goButton;

	public OpenFilesDialog() {
		super();
		initGUI();
	}
	

	private void initGUI() {
		int prefXSize = 600, prefYSize = 400;
		this.setMinimumSize(new java.awt.Dimension(prefXSize, prefYSize + 40));
		this.setSize(prefXSize, prefYSize + 40);
		this.setTitle("Multi-File-Manager - by JNH and SR (\u00a9 2016-26)");
		
		// New in V0.2.1: closing the window cancels (before, the plugin waited for "start processing" for ever)
		this.addWindowListener(new java.awt.event.WindowAdapter() {
			public void windowClosing(java.awt.event.WindowEvent we) {
				filesToOpen.clear();
				done = true;
			}
		});
		
		// Surface
		bgPanel = new JPanel();
		bgPanel.setLayout(new BoxLayout(bgPanel, BoxLayout.Y_AXIS));
		bgPanel.setVisible(true);
		bgPanel.setPreferredSize(new java.awt.Dimension(prefXSize, prefYSize - 20));
		{
			jScrollPane1 = new JScrollPane();
			jScrollPane1.setHorizontalScrollBarPolicy(30);
			jScrollPane1.setVerticalScrollBarPolicy(20);
			jScrollPane1.setPreferredSize(new java.awt.Dimension(prefXSize - 10, prefYSize - 60));
			bgPanel.add(jScrollPane1);
			{
				Liste1 = new JList<String>();
				jScrollPane1.setViewportView(Liste1);
				Liste1.setModel(new DefaultComboBoxModel<String>(new String[] { "" }));
			}
			{
				JPanel spacer = new JPanel();
				spacer.setMaximumSize(new java.awt.Dimension(prefXSize, 10));
				spacer.setVisible(true);
				bgPanel.add(spacer);
			}
			{
				JPanel bottom = new JPanel();
				bottom.setLayout(new BoxLayout(bottom, BoxLayout.X_AXIS));
				bottom.setMaximumSize(new java.awt.Dimension(prefXSize, 10));
				bottom.setVisible(true);
				bgPanel.add(bottom);
				int locHeight = 40;
				int locWidth3 = prefXSize / 4 - 60;
				{
					loadSingleFilesButton = new JButton();
					loadSingleFilesButton.addActionListener(this);
					loadSingleFilesButton.setText("select files individually");
					loadSingleFilesButton.setMinimumSize(new java.awt.Dimension(locWidth3, locHeight));
					loadSingleFilesButton.setVisible(true);
					loadSingleFilesButton.setVerticalAlignment(SwingConstants.BOTTOM);
					bottom.add(loadSingleFilesButton);
				}
					loadByPatternButtom = new JButton();
					loadByPatternButtom.addActionListener(this);
					loadByPatternButtom.setText("select files by pattern");
					loadByPatternButtom.setMinimumSize(new java.awt.Dimension(locWidth3, locHeight));
					loadByPatternButtom.setVisible(true);
					loadByPatternButtom.setVerticalAlignment(SwingConstants.BOTTOM);
					bottom.add(loadByPatternButtom);
				{
					removeFileButton = new JButton();
					removeFileButton.addActionListener(this);
					removeFileButton.setText("remove selected files");
					removeFileButton.setMinimumSize(new java.awt.Dimension(locWidth3, locHeight));
					removeFileButton.setVisible(true);
					removeFileButton.setVerticalAlignment(SwingConstants.BOTTOM);
					bottom.add(removeFileButton);
				}
				{
					goButton = new JButton();
					goButton.addActionListener(this);
					goButton.setText("start processing");
					goButton.setMinimumSize(new java.awt.Dimension(locWidth3, locHeight));
					goButton.setVisible(true);
					goButton.setVerticalAlignment(SwingConstants.BOTTOM);
					bottom.add(goButton);
				}
			}
		}
		getContentPane().add(bgPanel);
	}

	@Override
	public void actionPerformed(ActionEvent ae) {
		Object eventQuelle = ae.getSource();
		if (eventQuelle == loadSingleFilesButton) {
			String OS = System.getProperty("os.name").toUpperCase();			
			if(OS.contains("MAC")) {
				java.awt.FileDialog fd = new java.awt.FileDialog(this, "Select files to add to list.");
				fd.setDirectory(System.getProperty("user.dir", "."));
				if (dirsaved) {
					// New in V0.2.1: the folder of the last file, not the file
					fd.setDirectory(saved.getParent());
				}
				fd.setMultipleMode(true);
				fd.setMode(FileDialog.LOAD);
				fd.setVisible(true);
				
				File[] files = fd.getFiles();
				for (int i = 0; i < files.length; i++) {
					filesToOpen.add(files[i]);
					saved = files[i];
					dirsaved = true;
				}
			}else {
				JFileChooser chooser = new JFileChooser();
				chooser.setPreferredSize(new Dimension(600, 400));
				chooser.setCurrentDirectory(new File(System.getProperty("user.dir", ".")));
				if (dirsaved) {
					chooser.setCurrentDirectory(saved);
				}
				chooser.setMultiSelectionEnabled(true);
				Component frame = null;
				
				// New in V0.2.1: Cancel adds nothing (the highlighted files were added)
				File[] files = chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFiles() : new File[0];
				for (int i = 0; i < files.length; i++) {
					filesToOpen.add(files[i]);
					saved = files[i];
					dirsaved = true;
				}
			}
			updateDisplay();
		}
		if (eventQuelle == loadByPatternButtom) {
			matchPattern(System.getProperty("user.dir"));
			updateDisplay();
		}
		if (eventQuelle == removeFileButton) {
			int[] indices = Liste1.getSelectedIndices();
			for (int i = indices.length - 1; i >= 0; i--) {
			if (indices[i] < filesToOpen.size()) {
				filesToOpen.remove(indices[i]);
			}				
		}
			updateDisplay();
		}
		if (eventQuelle == goButton) {
			done = true;
			dispose();
		}

	}

	private void matchPattern(String rootPath) {
		String posFilePattern = "", negFilePattern = "", negDirPattern = "";

		JFileChooser fc = new JFileChooser();
		fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		fc.setMultiSelectionEnabled(true);
		fc.setCurrentDirectory(new File(rootPath));

		if (fc.showDialog(fc, "Choose directory to start pattern matching") != JFileChooser.APPROVE_OPTION) {
			return;
		}

		Stack<File> q = new Stack<File>();
		for (File f : fc.getSelectedFiles()) {
			q.push(f);
		}

		boolean posFileInputAsRegex = false, negFileInputAsRegex = false, negDirInputAsRegex = false;
		GenericDialog gd = new GenericDialog("Insert pattern matching parameters:");

		gd.addCheckbox("Input as Regex", posFileInputAsRegex);
		gd.setInsets(0, 50, 0);
		gd.addStringField("Enter pattern to be matched in filenames", posFilePattern, 16);

		gd.addCheckbox("Input as Regex", negFileInputAsRegex);
		gd.setInsets(0, 50, 0);
		gd.addStringField("Enter pattern in filenames to exclude files", negFilePattern, 16);

		gd.addCheckbox("Input as Regex", negDirInputAsRegex);
		gd.setInsets(0, 50, 0);
		gd.addStringField("Enter pattern in parent directories to exclude files", negDirPattern, 16);

		gd.showDialog();

		posFileInputAsRegex = gd.getNextBoolean();
		posFilePattern = gd.getNextString();
		negFileInputAsRegex = gd.getNextBoolean();
		negFilePattern = gd.getNextString();
		negDirInputAsRegex = gd.getNextBoolean();
		negDirPattern = gd.getNextString();
		if (gd.wasCanceled()) {
			return;
		}

		if (!posFileInputAsRegex) {
			posFilePattern = transformStringToRegex(posFilePattern);
		}
		if (!negFileInputAsRegex) {
			negFilePattern = transformStringToRegex(negFilePattern);
		}
		if (!negDirInputAsRegex) {
			negDirPattern = transformStringToRegex(negDirPattern);
		}
		if (posFilePattern.length() == 0)
			posFilePattern = ".*";	// New in V0.2.1: no pattern = every file (before: none)
		try {	// New in V0.2.1: a regex that does not compile is reported (it was thrown on the Swing thread)
			java.util.regex.Pattern.compile(posFilePattern);
			java.util.regex.Pattern.compile(negFilePattern);
			java.util.regex.Pattern.compile(negDirPattern);
		} catch (java.util.regex.PatternSyntaxException pe) {
			ij.IJ.error("Pattern matching", "Not a valid pattern:\n" + pe.getPattern() + "\n" + pe.getDescription());
			return;
		}

		File[] fid; // Files in Dir
		while (!q.isEmpty()) {
			fid = q.pop().listFiles();
			if (fid == null)
				continue;	// New in V0.2.1: a folder that cannot be read
			for (File f : fid) { // loop through files in dir
				if (f.isDirectory()) {
					// add to queue if negDirPattern can't be matched; New in V0.2.1: an excluded folder is skipped, not added as a file
					if (!f.getName().matches(negDirPattern))
						q.push(f);
				} else if (f.getName().matches(posFilePattern) && !f.getName().matches(negFilePattern)) {
					// add to file list if posPattern matches and negative Pattern doesn't
					filesToOpen.add(f);
				}
			}
		}		
		return;
	}

	/** A plain pattern as a regex: every character literal except "*", which stands for any text.
	 * New in V0.2.1: before, only "." was escaped, so "*.tif" threw an error and "img(1)" found nothing. */
	static String transformStringToRegex(String pattern) {
		if (pattern.length() == 0)
			return "";
		StringBuilder s = new StringBuilder(".*");
		String[] parts = pattern.split("\\*", -1);
		for (int i = 0; i < parts.length; i++) {
			if (i > 0)
				s.append(".*");
			if (parts[i].length() > 0)
				s.append(java.util.regex.Pattern.quote(parts[i]));
		}
		return s.append(".*").toString();
	}

	public void updateDisplay() {
		String resultsString[] = new String[filesToOpen.size()];
		for (int i = 0; i < filesToOpen.size(); i++) {
			resultsString[i] = (i + 1) + ": " + filesToOpen.get(i).getName();
		}
		Liste1.setListData(resultsString);
	}
	
}
