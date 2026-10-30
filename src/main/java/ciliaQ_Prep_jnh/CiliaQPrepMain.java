package ciliaQ_Prep_jnh;
/** ===============================================================================
* CiliaQ_Preparator Version 0.2.1
* 
* This program is free software; you can redistribute it and/or
* modify it under the terms of the GNU General Public License
* as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )
* 
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*  
* See the GNU General Public License for more details.
*  
* Copyright (C) Jan Niklas Hansen
* Date: September 29, 2019 (This Version: October 29, 2026)
*   
* For any questions please feel free to contact me (jan.hansen@uni-bonn.de).
* =============================================================================== */

import java.awt.*;
import java.awt.event.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.text.*;

import javax.swing.UIManager;

import ciliaQ_Prep_jnh.canny3d_thresholder.ProcessSettings;
import ij.*;
import ij.gui.*;
import ij.io.*;
import ij.measure.*;
import ij.plugin.*;
import ij.process.LUT;
import ij.plugin.frame.Recorder;
import java.awt.GraphicsEnvironment;

import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;
import ij.process.AutoThresholder.Method;

public class CiliaQPrepMain implements PlugIn, Measurements {
	//Name variables
	public static final String PLUGIN_NAME = "CiliaQ Preparator";
	public static final String PLUGIN_VERSION = "0.2.1";
	
	//Fix fonts
	public static final Font SUPER_HEADING_FONT = new Font("Sansserif", Font.BOLD, 16);
	public static final Font HEADING_FONT = new Font("Sansserif", Font.BOLD, 14);
	public static final Font SUB_HEADING_FONT = new Font("Sansserif", Font.BOLD, 12);
	public static final Font TEXT_FONT = new Font("Sansserif", Font.PLAIN, 12);
	public static final Font INSTRUCTIONS_FONT = new Font("Sansserif", Font.ITALIC, 12);
	public static final Font ROI_FONT = new Font("Sansserif", Font.PLAIN, 12);
	
	DecimalFormat df6 = new DecimalFormat("#0.000000");
	DecimalFormat df3 = new DecimalFormat("#0.000");
	DecimalFormat df0 = new DecimalFormat("#0");
	
	static SimpleDateFormat NameDateFormatter = new SimpleDateFormat("yyMMdd_HHmmss");
	static SimpleDateFormat FullDateFormatter = new SimpleDateFormat("yyyy-MM-dd	HH:mm:ss");
	static SimpleDateFormat FullDateFormatter2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	
	//Progress Dialog
	ProgressReporter progress;	
	boolean showGUIs = true;	// False under --headless or in a macro to suppress dialogs and switch to IJ.log.
	boolean processingDone = false;	
	boolean continueProcessing = true;
	
	//-----------------define params for Dialog-----------------
	static final String[] taskVariant = {"active image in FIJI","multiple images (open multi-task manager)", "all images open in FIJI"};
	String selectedTaskVariant = taskVariant[1];
	int tasks = 1;
	
	final static String[] settingsMethod = {"manually enter preferences", "load preferences from existing CiliaQ Preparator metadata file"};	
	String selectedSettingsVariant = settingsMethod [0];
	
	int nChannels = 1;
	int [] channelIDs = new int [] {1,2,3};
	
	public static final String STACK_POOLED = "apply threshold determined in the stack histogram";
	public static final String STACK_MIP =
			"apply threshold determined in a maximum-intensity-projection";
	public static final String STACK_PER_SLICE = "apply a separate threshold for each slice";
	public static final String STACK_NO_STACK = "input image is no stack";
	final static String[] stackMethod = { STACK_POOLED, STACK_MIP, STACK_PER_SLICE, STACK_NO_STACK };
	
	/**	
	 * This array is already here to allow renaming methods in the future if needed
	 * For now this is obsolete since there is just an entry that is identical anyhow
	 * Adapt in future if needed
	 *	*/
	final static String[][] legacyStackMethod = {
			{ "apply threshold determined in the stack histogram", STACK_POOLED } };
	
	/**	@return the current label for a saved one, or null if it matches nothing. */
	static String matchStackMethod(String line){
		for(int i = 0; i < stackMethod.length; i++){
			if(line.contains(stackMethod [i]))	return stackMethod [i];
		}
		for(int i = 0; i < legacyStackMethod.length; i++){
			if(line.contains(legacyStackMethod [i][0]))	return legacyStackMethod [i][1];
		}
		return null;
	}
	
	String chosenStackMethods [] = new String [] {STACK_MIP, STACK_MIP, STACK_MIP};

	/**	Introduced in V0.2.1: 2D median pre-filter, applied BEFORE the first Gaussian.
	 *	Radius in PIXELS. Default 1 = a 3x3 neighbourhood*/
	boolean medianFilter [] = new boolean [] {false, false, false};
	double medianRadius [] = new double [] {1.0, 1.0, 1.0};
	
	/**
	 * Gaussian blur applied BEFORE background subtraction/division.
	 * Introduced in V0.2.0, because we practically identified this to be helpful in many cases.
	 */
	boolean preBlur [] = new boolean [] {false,false,false};
	double preBlurRadius [] = new double [] {2.0, 2.0, 2.0};
	
	boolean subtractBackground [] = new boolean [] {false, false, false};
	boolean divideBackground [] = new boolean [] {false, false, false};
	boolean includeDuplicateChannel [] = new boolean [] {true,true,true};
	double subtractBGRadius [] = new double [] {10.0, 10.0, 10.0};
	double divideBGRadius [] = new double [] {3.0, 3.0, 3.0};
	
	/**
	 * Gaussian blur applied AFTER background subtraction/division.
	 * Like every other preprocessing step this affects the segmentation only - the saved
	 * intensities are the original ones.
	 */
	boolean additionalBlur [] = new boolean [] {false,false,false};
	double additionalBlurRadius [] = new double [] {0.5, 0.5, 0.5};
	
	boolean separateTimesteps [] = new boolean [] {false, false, false};
	
	//New methods in v0.2.1:
	public static final String RIDGE_DETECTION = "RIDGE DETECTION (filaments)";
	public static final String SPOT_DETECTION = "SPOT DETECTION (LoG/DoG)";
	public static final String EXPANSION_SEEDS = "EXPANSION from seeds (labels)";
	String [] algorithm = {"Default", "IJ_IsoData", "Huang", "Intermodes", "IsoData", "Li", "MaxEntropy", "Mean", 
			"MinError", "Minimum", "Moments", "Otsu", "Percentile", "RenyiEntropy", "Shanbhag", "Triangle", 
			"Yen", "CANNY 3D", "CUSTOM threshold", "HYSTERESIS threshold",
			RIDGE_DETECTION,
			SPOT_DETECTION,
			EXPANSION_SEEDS,
			ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.GLOBAL,
			ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.LOCAL,
			ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.NAME};
	
	String chosenAlgorithms [] = new String [] {"RenyiEntropy","RenyiEntropy","RenyiEntropy"};
	double customThr [] = new double [] {0.0, 0.0, 0.0};
	
	String [] hystAlgorithms = {"CUSTOM threshold", "Default", "IJ_IsoData", "Huang", "Intermodes", "IsoData", "Li", "MaxEntropy", "Mean", 
			"MinError", "Minimum", "Moments", "Otsu", "Percentile", "RenyiEntropy", "Shanbhag", "Triangle", 
			"Yen"};
	double customLowThr [] = new double [] {0.0, 0.0, 0.0};
	double customHighThr [] = new double [] {0.0, 0.0, 0.0};
	String chosenLowAlg [] = new String [] {"Triangle","Triangle","Triangle"};
	String chosenHighAlg [] = new String [] {"Otsu","Otsu","Otsu"};
	
	static final String[] outputVariant = {"save as filename + suffix 'CQP'", "save as filename + suffix 'CQP' + date"};
	String chosenOutputName = outputVariant[0];
	
	static final String[] intensityVariant = {"Keep intensities above threshold (creates a background-removed image)",
			"Set intensities above thresholds to maximum possible intensity value (creates a binary image)"};
	boolean keepIntensities = false;
	String chosenImageStyle = intensityVariant[0];
	
	static final String[] nrFormats = {"US (0.00...)", "Germany (0,00...)"};
	String ChosenNumberFormat = nrFormats[0];
	
	ProcessSettings cannySettings [];
	
	// Per-channel settings for new methods introduced in V0.2.1:
	ciliaQ_Prep_jnh.ridge_detection.RidgeSettings ridgeSettings [];
	ciliaQ_Prep_jnh.spot_detection.SpotSettings spotSettings [];
	ciliaQ_Prep_jnh.expansion.ExpansionSettings expansionSettings [];
	ciliaQ_Prep_jnh.fraction_threshold.FractionSettings fractionSettings [];
	ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings phansalkarSettings [];

	/**	Macro values that were not recognised, collected while reading and reported once the run starts, added in V0.2.1 */
	java.util.ArrayList<String> macroWarnings;

	/**
	 *	New from V0.2.1 on: Per-channel counters for the ZERO-INTENSITY LOSS warning.
	 *	What this refers to:
	 *	A voxel can pass the threshold and still be written as 0. The threshold is applied to the
	 *	PREPROCESSED channel while "keep intensities" writes the ORIGINAL value, so wherever a blur or a
	 *	background division has extended the mask over background that is exactly 0, the output is 0 -
	 *	and CiliaQ treats only voxels > 0 as foreground, so it sees a smaller object than the
	 *	segmentation found.
	 */
	long maskVoxelCount [];
	long zeroIntensityVoxelCount [];
	int currentChannelIndex = -1;
	
	/**
	 * New from V0.2.1 on: Ridge-detection labels waiting to be written, [channel index][frame].
	 */
	ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.Result pendingRidgeLabels [][];
	int pendingRidgeChannel [];

	/**
	 * New from V0.2.1 on: Expansion labels waiting to be written, [channel index][frame][voxel], flat in
	 * (z * height + y) * width + x order.
	 */
	int pendingExpansionLabels [][][];
	int pendingExpansionChannel [];
	int pendingExpansionMax [];
	
	// Settings for keeping computer on
	boolean keepAwake;
	Robot robo;
	
	/** Avoiding dual run of CiliaQ Preparator in same FIJI
	 *  True while a CiliaQ Preparator run is executing in this JVM. A second concurrent run in the same
	 *  Fiji would share the Ridge Detection plugin's static Line.idCounter (and other static state),
	 *  making both runs' ridge results non-reproducible and potentially interleaving the counter reset.
	 *  So a second run is refused until the first finishes. Separate Fiji processes have their own JVM and
	 *  are unaffected. */
	private static volatile boolean runInProgress = false;
	
public void run(String arg) {
synchronized(CiliaQPrepMain.class){
    if(runInProgress){
        IJ.error(PLUGIN_NAME + " V" + PLUGIN_VERSION, "A " + PLUGIN_NAME + " run is already in progress in this Fiji."
            + "\nPlease wait for it to finish before starting another or cancel and restart Fiji."
            + "\n(Running two at once in the same Fiji would make ridge-detection results non-reproducible,"
            + "\nbecause the Ridge Detection plugin keeps shared internal state.)");
        return;
    }
    runInProgress = true;
}
try {	
	
	
	//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
	//-----------Eventually read settings from macro input------------------------
	//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
	boolean showDialogs = true;
	boolean record = false;
	if(IJ.macroRunning() && Macro.getOptions() != null && Macro.getOptions().length() > 0){
		if(!readSettingsFromMacroString(Macro.getOptions())){
			return;
		}
		if(!validateSettings()){
			return;
		}
		showDialogs = false;
	}else if(GraphicsEnvironment.isHeadless()){
		IJ.log(PLUGIN_NAME + ": running headless but no macro options were given - nothing to do."
				+ " Pass settings=[/path/to/..._CQP.txt] or the c<n>- keys.");
		return;
	}

	if(showDialogs) {
		// New in V0.2.0
		if(Recorder.record){
			// The recorder cannot capture these dialogs, so it is suspended here.
			// Later we provide a custom call string.
			record = true;
			Recorder.record = false;
		}
		
		GenericDialog gd = new GenericDialog(PLUGIN_NAME + " on " + System.getProperty("os.name") + "");	
		//show Dialog-----------------------------------------------------------------
		//.setInsets(top, left, bottom)
		gd.setInsets(0,0,0);	gd.addMessage(PLUGIN_NAME + ", Version " + PLUGIN_VERSION + ", \u00a9 2019-2023 JN Hansen", SUPER_HEADING_FONT);
		gd.setInsets(5,0,0);	gd.addChoice("process ", taskVariant, selectedTaskVariant);
		gd.setInsets(0,0,0);	gd.addMessage("The plugin processes .tif images or calls a BioFormats plugin to open different formats.", INSTRUCTIONS_FONT);
		gd.setInsets(0,0,0);	gd.addMessage("The BioFormats plugin is preinstalled in FIJI / can be manually installed to ImageJ.", INSTRUCTIONS_FONT);
		
		gd.setInsets(10,0,0);	gd.addChoice("Preferences: ", settingsMethod, selectedSettingsVariant);
		gd.setInsets(0,0,0);	gd.addMessage("Note: you may only load preferences from CiliaQ Preparator version v0.0.6 or higher.", INSTRUCTIONS_FONT);
		
		gd.setInsets(10,0,0);	gd.addMessage("GENERAL SETTINGS:", HEADING_FONT);	
		gd.setInsets(5,0,0);	gd.addChoice("Output image name: ", outputVariant, chosenOutputName);
		gd.setInsets(5,0,0);	gd.addChoice("output number format", nrFormats, nrFormats[0]);
		gd.setInsets(5,0,0);	gd.addCheckbox("Keep computer awake during processing", keepAwake);
		
		gd.addHelp("https://github.com/hansenjn/ciliaq/");
		
		gd.showDialog();
		//show Dialog-----------------------------------------------------------------
	
		//read and process variables--------------------------------------------------
		selectedTaskVariant = gd.getNextChoice();
		selectedSettingsVariant = gd.getNextChoice();
		chosenOutputName = gd.getNextChoice();	
		ChosenNumberFormat = gd.getNextChoice();
		if(ChosenNumberFormat.equals(nrFormats[0])){ //US-Format
			df6.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
			df3.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
			df0.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
		}else if (ChosenNumberFormat.equals(nrFormats[1])){
			df6.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.GERMANY));
			df3.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.GERMANY));
			df0.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.GERMANY));
		}
		keepAwake = gd.getNextBoolean();
		
		//read and process variables--------------------------------------------------
		if (gd.wasCanceled()) {
			if(record) Recorder.record = true;	// restore recorder before aborting
			return;
		}
				
		if(selectedSettingsVariant.equals(settingsMethod [0])){
			if(!enterSettings()) {
				if(record) Recorder.record = true;	// restore recorder before aborting
				return;
			}
			
			/*
			 *	SECONDARY SETTINGS DIALOGS, ONE PER CHANNEL, IN CHANNEL ORDER.
			 *
			 *	v0.2.1 replaced seven separate per-method loops with this single loop. With the old
			 *	shape the dialogs appeared grouped BY METHOD, so a user whose channel 1 used HYSTERESIS
			 *	and channel 2 used RIDGE was asked for the ridge settings first - hysteresis was the last
			 *	block of the seven. Channel order is what the user expects, and one loop cannot drift
			 *	out of step with the dropdown the way seven copies can.
			 */
			cannySettings = new ciliaQ_Prep_jnh.canny3d_thresholder.ProcessSettings [channelIDs.length];
			ridgeSettings = new ciliaQ_Prep_jnh.ridge_detection.RidgeSettings [channelIDs.length];
			spotSettings = new ciliaQ_Prep_jnh.spot_detection.SpotSettings [channelIDs.length];
			expansionSettings = new ciliaQ_Prep_jnh.expansion.ExpansionSettings [channelIDs.length];
			fractionSettings = new ciliaQ_Prep_jnh.fraction_threshold.FractionSettings [channelIDs.length];
			phansalkarSettings = new ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings [channelIDs.length];
			
			/*
			 *	Prefill settings for Hysteresis Threshold
			 * */
			customLowThr = new double [channelIDs.length];
			customHighThr = new double [channelIDs.length];
			chosenLowAlg = new String [channelIDs.length];
			chosenHighAlg = new String [channelIDs.length];
			
			Arrays.fill(chosenLowAlg,"Triangle");
			Arrays.fill(chosenHighAlg,"Otsu");
						
			for(int i = 0; i < channelIDs.length; i++){
				String task = "channel " + i + " with channel nr " + channelIDs [i];
				String alg = chosenAlgorithms [i];
				try {
					if("CANNY 3D".equals(alg)){
						cannySettings [i] = ProcessSettings.initByGD(task);
					}else if(RIDGE_DETECTION.equals(alg)){
						ridgeSettings [i] = ciliaQ_Prep_jnh.ridge_detection.RidgeSettings.initByGD(task);
					}else if(SPOT_DETECTION.equals(alg)){
						/*	No image is open yet - `imp` is declared further down - so this dialog cannot
						 *	pre-fill a bit-depth-dependent default. The hint names both values and
						 *	SpotSettings.sensitivityWarning() re-checks during processing. */
						spotSettings [i] = ciliaQ_Prep_jnh.spot_detection.SpotSettings.initByGD(task);
					}else if(EXPANSION_SEEDS.equals(alg)){
						/*	Its seed parameters are plain numbers in this one dialog rather than a nested
						 *	ridge/spot dialog, so choosing a seed source never opens a third dialog. */
						expansionSettings [i] = ciliaQ_Prep_jnh.expansion.ExpansionSettings.initByGD(task);
					}else if(ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.isFractionMethod(alg)){
						fractionSettings [i] = ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.initByGD(
								task, ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.LOCAL.equals(alg));
					}else if(ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.NAME.equals(alg)){
						phansalkarSettings [i] = ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.initByGD(task);
					}else if("HYSTERESIS threshold".equals(alg)){
						/*	The only method that is initialized through a settings class,
						 *	it fills four parallel arrays rather. */
						if(!requestHysteresisPrefs(task, i)){
							if(record) Recorder.record = true;	// restore recorder before aborting
							return;
						}
					}
				} catch (Exception e) {
					if(record) Recorder.record = true;	// restore recorder before aborting
					return;
				}
			}
			
			
		}else if(!importSettings()) {
			IJ.error("Preferences could not be loaded due to file error...");
			if(record) Recorder.record = true;	// restore recorder before aborting
			return;
		}
		
		//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
		//---------------------end-GenericDialog-end----------------------------------
		//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
		
		/*
		 * Check for input error
		 * */		
		if(!validateSettings()){
			if(record) Recorder.record = true;	// restore recorder before aborting
			return;
		}	
	}	// end of if(showDialogs)

	if(record){
		Recorder.record = true;
	    if(Recorder.getInstance() != null){
	        Recorder.setCommand(null);   // cancel ImageJ's auto-recorded menu command
	    }
		Recorder.recordString("run(\"" + PLUGIN_NAME + " v" + PLUGIN_VERSION + "\", \"" + createRecordString() + "\");\n");
	}
	
	//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
	//------------------------------ LOAD FILES ----------------------------------
	//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&		
	
	String name [] = {"",""};
	String dir [] = {"",""};
	ImagePlus allImps [] = new ImagePlus [2];
//	RoiEncoder re;
	{
		//Improved file selector
		try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception e){}
		if(selectedTaskVariant.equals(taskVariant[1])){
			OpenFilesDialog od = new OpenFilesDialog ();
			od.setLocation(0,0);
			od.setVisible(true);
			
			od.addWindowListener(new java.awt.event.WindowAdapter() {
		        public void windowClosing(WindowEvent winEvt) {
		        	return;
		        }
		    });
		
			//Waiting for od to be done
			while(od.done==false){
				try{
					Thread.sleep(100);
			    }catch(Exception e){
			    }
			}
			
			tasks = od.filesToOpen.size();
			name = new String [tasks];
			dir = new String [tasks];
			for(int task = 0; task < tasks; task++){
				name[task] = od.filesToOpen.get(task).getName();
				dir[task] = od.filesToOpen.get(task).getParent() + System.getProperty("file.separator");
			}		
		}else if(selectedTaskVariant.equals(taskVariant[0])){
			try {
				if(WindowManager.getIDList()==null){
					new WaitForUserDialog("Plugin canceled - no image open in FIJI!").show();
					return;
				}
				if(WindowManager.getCurrentImage().changes) {
					new WaitForUserDialog("Plugin canceled - the active image had unsaved changes!"
							+ "\nCiliaQ can only process images that are saved!"
							+ "\nSave the image and retry running CiliaQ_Preparator...").show();
					return;
				}
				FileInfo info = WindowManager.getCurrentImage().getOriginalFileInfo();
				name [0] = info.fileName;	//get name
				dir [0] = info.directory;	//get directory
				tasks = 1;
			}catch(Exception e) {
				new WaitForUserDialog("Unclear error when loading the active image!"
						+ "\nTry to restart FIJI, open the image again, and run CiliaQ_Preparator again!"
						+ "\nDetailed debugging information printed to LOG window.").show();

				IJ.log("Detailed debugging information:"
						+ "\nError message: " + e.getMessage()
						+ "\nError localized message: " + e.getLocalizedMessage()
						+ "\nError cause: " + e.getCause() 
						+ "\nDetailed message:"
						+ "\n" + stackTraceToString(e));
				
				return;
			}			
		}else if(selectedTaskVariant.equals(taskVariant[2])){	// all open images
			try {
				if(WindowManager.getIDList()==null){
					new WaitForUserDialog("Plugin canceled - no image open in FIJI!").show();
					return;
				}
				int IDlist [] = WindowManager.getIDList();
				tasks = IDlist.length;	
				if(tasks == 1){
					selectedTaskVariant=taskVariant[0];
					if(WindowManager.getCurrentImage().changes) {
						new WaitForUserDialog("Plugin canceled - the active image had unsaved changes!"
								+ "\nCiliaQ can only process images that are saved!"
								+ "\nSave the image and retry running CiliaQ_Preparator...").show();
						return;
					}
					FileInfo info = WindowManager.getCurrentImage().getOriginalFileInfo();
					name [0] = info.fileName;	//get name
					dir [0] = info.directory;	//get directory
				}else{
					name = new String [tasks];
					dir = new String [tasks];
					allImps = new ImagePlus [tasks];
					for(int i = 0; i < tasks; i++){
						allImps[i] = WindowManager.getImage(IDlist[i]);
						if(allImps[i].changes) {
							new WaitForUserDialog("Plugin canceled - one active image had unsaved changes!"
									+ "\nCiliaQ can only process images that are saved!"
									+ "\nSave the image and retry running CiliaQ_Preparator..."
									+ "\n\nTitle of the unsaved image: " + allImps[i].getTitle()).show();
							return;
						}
						FileInfo info = allImps[i].getOriginalFileInfo();
						name [i] = info.fileName;	//get name
						dir [i] = info.directory;	//get directory
					}		
				}
			}catch(Exception e) {
				new WaitForUserDialog("Unclear error when loading the active images!"
						+ "\nTry to restart FIJI, open the images again, and run CiliaQ_Preparator again!"
						+ "\nDetailed debugging information printed to LOG window.").show();
				
				IJ.log("Detailed debugging information:"
						+ "\nError message: " + e.getMessage()
						+ "\nError localized message: " + e.getLocalizedMessage()
						+ "\nError cause: " + e.getCause() 
						+ "\nDetailed message:"
						+ "\n" + stackTraceToString(e));
				
				return;
			}					
		}
	}
	
	//For BioFormats - screen for series and add tasks accordingly
	ImporterOptions bfOptions;
	int series [] = new int [tasks];
	int totSeries [] = new int [tasks];
	Arrays.fill(series, 0);
	Arrays.fill(totSeries, 1);

//	String filesList = "Files to process:\n";
	if(selectedTaskVariant.equals(taskVariant[1])){
		for(int i = tasks-1; i >= 0; i--){
			IJ.showProgress((tasks-i)/tasks);
			try {
				bfOptions = new ImporterOptions();
				bfOptions.setId(""+dir[i]+name[i]+"");
				bfOptions.setVirtual(true);
				bfOptions.setOpenAllSeries(true);
				ImagePlus[] imps = BF.openImagePlus(bfOptions);
				if(imps.length > 1) {
					String [] nameTemp = new String [name.length+imps.length-1], 
							dirTemp = new String [name.length+imps.length-1];
					int [] seriesTemp = new int [nameTemp.length],
							totSeriesTemp = new int [nameTemp.length]; 
					for(int j = 0; j < i; j++) {
						nameTemp [j] = name [j]; 
						dirTemp [j] = dir [j];
						seriesTemp [j] = series [j];
						totSeriesTemp [j] = totSeries [j];
						
					}
					for(int j = 0; j < imps.length; j++) {
						nameTemp [i+j] = name [i]; 
						dirTemp [i+j] = dir [i];
						seriesTemp [i+j] = j;
						totSeriesTemp [i+j] = imps.length;
					}
					for(int j = i+1; j < name.length; j++) {
						nameTemp [j+imps.length-1] = name [j]; 
						dirTemp [j+imps.length-1] = dir [j];
						seriesTemp [j+imps.length-1] = series [j];
						totSeriesTemp [j+imps.length-1] = totSeries [j];
					}
					
					//copy arrays

					tasks = nameTemp.length;
					name = new String [tasks];
					dir = new String [tasks];
					series = new int [tasks];
					totSeries = new int [tasks];
					
					for(int j = 0; j < nameTemp.length; j++) {
						name [j] = nameTemp [j];
						dir [j] = dirTemp [j];
						series [j] = seriesTemp [j];
						totSeries [j] = totSeriesTemp [j];
//						filesList += name[j] + "\t" + dir[j] + "\t" + series[j] + "\t" + totSeries[j] + "\n";
					}
				}
			} catch (Exception e) {
				IJ.log(e.getCause().getLocalizedMessage());
				IJ.log(e.getCause().getMessage());
				e.printStackTrace();
			}
		}
	}
	
	
	//add progressDialog or ConsoleProgress to follow processing progress
		if(showGUIs && !GraphicsEnvironment.isHeadless()){
			ProgressDialog dialog = new ProgressDialog(name, tasks);
			progress = dialog;
			dialog.setLocation(0,0);
			dialog.setVisible(true);
			dialog.addWindowListener(new java.awt.event.WindowAdapter() {
		        public void windowClosing(WindowEvent winEvt) {
		        	if(processingDone==false){
		        		IJ.error("Script stopped...");
		        	}
		        	continueProcessing = false;	        	
		        	return;
		        }
			});
		}else{
			progress = new ConsoleProgress(name, tasks);
		}
			
	
   	ImagePlus imp; 	  	
   	boolean backgroundPref = Prefs.blackBackground;
	Prefs.blackBackground =  true;
	boolean isVirtual;
	if(keepAwake) {
   		try {
			robo = new Robot();
		} catch (AWTException e) {
			keepAwake = false;
			progress.notifyMessage("Robot that moves the mouse to keep the computer awake could not be hired - Stay-awake mode was disabled.", ProgressReporter.NOTIFICATION);
		}
   	}
	
	boolean recordPref = Recorder.record;
	Recorder.record = false;
	try{for(int task = 0; task < tasks; task++){
		if(keepAwake) {
			stayAwake();
		}
		running: while(continueProcessing){
			Date startDate = new Date();
			progress.updateBarText("in progress...");
			//Check for problems
			if(name[task].contains(".") && name[task].substring(name[task].lastIndexOf("."),name[task].length()).equals(".txt")){
				progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": A file is no image! Could not be processed!", ProgressReporter.ERROR);
				break running;
			}
			if(name[task].contains(".") && name[task].substring(name[task].lastIndexOf("."),name[task].length()).equals(".zip")){	
				progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": A file is no image! Could not be processed!", ProgressReporter.ERROR);
				break running;
			}
			//Check for problems

			if(keepAwake) {
				stayAwake();
			}
			
			//open Image
		   	try{
		   		if(selectedTaskVariant.equals(taskVariant[1])){
		   			if(name[task].contains(".tif")){
		   				//TIFF file
		   				imp = IJ.openImage(""+dir[task]+name[task]+"");		
		   			}else{
		   				//bio format reader
		   				bfOptions = new ImporterOptions();
		   				bfOptions.setId(""+dir[task]+name[task]+"");
		   				bfOptions.setVirtual(false);
		   				bfOptions.setAutoscale(true);
		   				bfOptions.setColorMode(ImporterOptions.COLOR_MODE_COMPOSITE);
		   				for(int i = 0; i < totSeries[task]; i++) {
		   					if(i==series[task]) {
		   						bfOptions.setSeriesOn(i, true);
		   					}else {
		   						bfOptions.setSeriesOn(i, false);
		   					}
		   				}
		   				ImagePlus [] imps = BF.openImagePlus(bfOptions);
//			   				IJ.run("Bio-Formats", "open=[" +dir[task] + name[task]
//			   						+ "] autoscale color_mode=Default rois_import=[ROI manager] view=Hyperstack stack_order=XYCZT");
		   				imp = imps [0];	
		   				imp.setDisplayMode(IJ.COMPOSITE);
		   			}
		   			imp.hide();
					imp.deleteRoi();
//			   			imp = IJ.openImage(""+dir[task]+name[task]+"");			   			
//						imp.deleteRoi();
		   		}else if(selectedTaskVariant.equals(taskVariant[0])){
		   			imp = WindowManager.getCurrentImage();
		   			imp.deleteRoi();
		   		}else{
		   			imp = allImps[task];
		   			imp.deleteRoi();
		   		}
		   	}catch (Exception e) {
		   		progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": file is no image - could not be processed!", ProgressReporter.ERROR);
				break running;
			}
		   	//open Image
		   	
		   	if(keepAwake) {
				stayAwake();
			}
		   	
		   	//Checking image
		   	if(imp.getBitDepth() == 24) {
	   			progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": file is an RGB - RGB images cannot be processed! Convert to multi-channel stack for processing!", ProgressReporter.ERROR);
				break running;
	   		}
	   		
		   	//Checking image for being a virtual stack
		   	isVirtual = false;
	   		if(imp.getStack().isVirtual()) {
	   			isVirtual = true;
	   			imp = imp.duplicate();
	   			progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": Image " + name [task] + " was a virtual stack image. Copied into work space for processing!", ProgressReporter.LOG);
	   		}
	   		
	   		//Checks that require the open image (Introduced in V0.2.0)
		   	if(!validateAgainstImage(imp, task)){
		   		break running;
		   	}
		   	
		   	//Create Outputfilename
		   	progress.updateBarText("Create output filename");				
			String filePrefix;
			if(name[task].contains(".")){
				filePrefix = name[task].substring(0,name[task].lastIndexOf("."));
			}else{
				filePrefix = name[task];
			}
			if(totSeries [task] > 1) {
				filePrefix += "_s" + (series[task] + 1);
			}
			
			filePrefix += "_CQP";
			
			if(chosenOutputName.equals(outputVariant[1])){
				//saveDate
				filePrefix += "_" + NameDateFormatter.format(startDate);
			}
			
			filePrefix = dir[task] + filePrefix;
		   	
			if(keepAwake) {
				stayAwake();
			}
			
		/******************************************************************
		*** 						Processing							***	
		*******************************************************************/
		   	ImagePlus tempImp, procImp;
		   	double thresholds [], threshold, thresholdsHyst [][];
		   	procImp = imp.duplicate();
		   	procImp.setCalibration(imp.getCalibration());
		   	procImp.deleteRoi();
		   	procImp.hide();
		   	
			//start logging metadata
			// V0.2.0: a plain text collector rather than ij.text.TextPanel, whose AWT
			// Scrollbars cannot be constructed under --headless.
			TextCollector tp1 = new TextCollector("results");
			addSettingsBlockToPanel(tp1,  startDate, name[task], totSeries[task]>1, series[task], imp);
			
			/*	V0.2.1: Report any unrecognised macro values now that there is somewhere visible to report them. */
			if(macroWarnings != null && !macroWarnings.isEmpty()){
				for(int w = 0; w < macroWarnings.size(); w++){
					progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": "
							+ macroWarnings.get(w), ProgressReporter.NOTIFICATION);
					tp1.append("WARNING	macro settings:	" + macroWarnings.get(w));
				}
			}
			
			tp1.append("");
			
			//processing
			int addC = 0;
		   	for(int c = 0; c < channelIDs.length; c++){
		   		if(keepAwake) {
					stayAwake();
				}
		   		{
		   			if(includeDuplicateChannel [c]){
		   				addC ++;
		   			}
		   			
		   			tempImp = copyChannel(procImp, channelIDs[c], false, false);
		   			
		   			currentChannelIndex = c; // so segmentImage knows where to count
		   			if(medianFilter [c]){	// New in V0.2.1
		   				// A median filter suppresses salt-and-pepper noise while preserving edges, which a Gaussian cannot;
		   				progress.updateBarText("Median filter for channel " + channelIDs [c] + " ...");
		   				applyMedianFilter(tempImp, medianRadius [c]);
		   			}
		   			
		   			if(preBlur [c]){
		   				progress.updateBarText("Pre-segmentation blur for channel " + channelIDs [c] + " ...");
		   				tempImp = blurGaussian(tempImp, preBlurRadius [c]);
		   			}
		   			
		   			if(subtractBackground [c]){
		   				progress.updateBarText("Subtract background for channel " + channelIDs [c] + " ...");		
			   			if(tempImp.getStackSize()>1){
				   			IJ.run(tempImp, "Subtract Background...", "rolling=" + subtractBGRadius [c] + " stack");
				   		}else{
				   			IJ.run(tempImp, "Subtract Background...", "rolling=" + subtractBGRadius [c]);
				   		}
			   		}
		   			
		   			if(keepAwake) {
						stayAwake();
					}
		   			
		   			if(divideBackground [c]){
		   				progress.updateBarText("Divide by background for channel " + channelIDs [c] + " ...");	
			   			tempImp = divideByBackground(tempImp, divideBGRadius [c]);
			   		}
		   			
		   			if(keepAwake) {
						stayAwake();
					}
		   			
		   			if(additionalBlur [c]) {
		   				progress.updateBarText("Blur " + channelIDs [c] + " ...");
		   				tempImp = blurGaussian(tempImp, additionalBlurRadius [c]);
		   			}
		   			
		   			if(keepAwake) {
						stayAwake();
					}
		   			
		   			if(chosenAlgorithms [c].equals("CANNY 3D")){
		   				progress.updateBarText("Segment channel " + channelIDs [c] + " with CANNY 3D ...");
		   				try {
		   					segmentUsingCanny3D(tempImp, progress, cannySettings [c], procImp, channelIDs [c]); 				   				
			   			}catch(Exception e) {
			   				progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": Applying Canny 3D failed."
			   						+ "\nVerify that you have the 3D ImageJ Suite installed - for more information see"
			   						+ "\nhttps://github.com/hansenjn/CiliaQ/wiki/Installing-CiliaQ"
			   						+ "\nIf you had the 3D ImageJ Suite installed, please report the following information as an issue on the CiliaQ github page or contact developer with the error information:"
									+ "\nError message: " + e.getMessage()
									+ "\nError localized message: " + e.getLocalizedMessage()
									+ "\nError cause: " + e.getCause() 
									+ "\nDetailed message:"
									+ "\n" + stackTraceToString(e), ProgressReporter.ERROR);
							break running;
			   			}		   				
			   			tempImp.changes = false;
			   			tempImp.close();
		   			}else if(chosenAlgorithms [c].equals("HYSTERESIS threshold")){
		   				progress.updateBarText("Segment channel " + channelIDs [c] + " with HYSTERESIS threshold ...");
		   				tp1.append("Hysteresis threshold(s) for channel " + channelIDs [c]);
		   				thresholdsHyst = getHysteresisThresholds(tempImp, c, separateTimesteps [c], progress);
			   			tp1.append("	Time step #	Low Threshold	High Threshold");
			   			for(int t = 0; t < procImp.getNFrames(); t++){
		   					tp1.append("	" + df0.format(t+1) + "	" + df3.format(thresholdsHyst[t][0]) + "	" + df3.format(thresholdsHyst[t][1]));				
			   			}
			   			warnOnHysteresisThresholdOrder(thresholdsHyst, channelIDs [c], task);   // New warning in V0.2.0
			   			
			   			try {
				   			segmentUsingHysteresis(tempImp, progress, thresholdsHyst, procImp, channelIDs [c]);			   				
			   			}catch(Exception e) {
			   				progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": Hysteresis Thresholding failed."
			   						+ "\nVerify that you have the 3D ImageJ Suite installed - for more information see"
			   						+ "\nhttps://github.com/hansenjn/CiliaQ/wiki/Installing-CiliaQ"
			   						+ "\nIf you had the 3D ImageJ Suite installed, please report the following information as an issue on the CiliaQ github page or contact developer with the error information:"
									+ "\nError message: " + e.getMessage()
									+ "\nError localized message: " + e.getLocalizedMessage()
									+ "\nError cause: " + e.getCause() 
									+ "\nDetailed message:"
									+ "\n" + stackTraceToString(e), ProgressReporter.ERROR);
							break running;
			   			}
			   			tempImp.changes = false;
			   			tempImp.close();
		   			}else if(chosenAlgorithms [c].equals(SPOT_DETECTION)){
		   				progress.updateBarText("Segment channel " + channelIDs [c] + " with SPOT DETECTION ...");
		   				try {
		   					segmentUsingSpotDetection(tempImp, progress, spotSettings [c],
		   							procImp, channelIDs [c], tp1);
		   				}catch(Exception e) {
		   					progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": Spot detection failed."
		   							+ "\nError message: " + e.getMessage()
									+ "\nError localized message: " + e.getLocalizedMessage()
									+ "\nError cause: " + e.getCause() 
									+ "\nDetailed message:"
									+ "\n" + stackTraceToString(e), ProgressReporter.ERROR);
		   					break running;
		   				}
		   				tempImp.changes = false;
		   				tempImp.close();
		   			}else if(chosenAlgorithms [c].equals(EXPANSION_SEEDS)){
		   				progress.updateBarText("Segment channel " + channelIDs [c] + " with EXPANSION from seeds ...");
		   				try {
		   					procImp = segmentUsingExpansion(tempImp, progress, expansionSettings [c],
		   							procImp, channelIDs [c], c, c, tp1);
		   				}catch(Exception e) {
		   					progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": Expansion from seeds failed."
		   							+ "\nIf the seed source is ridge detection, verify that the Ridge Detection"
		   							+ " plugin and commons-lang3 are installed."
		   							+ "\nError message: " + e.getMessage()
									+ "\nError localized message: " + e.getLocalizedMessage()
									+ "\nError cause: " + e.getCause() 
									+ "\nDetailed message:"
									+ "\n" + stackTraceToString(e), ProgressReporter.ERROR);
		   					break running;
		   				}
		   				tempImp.changes = false;
		   				tempImp.close();
		   			}else if(chosenAlgorithms [c].equals(RIDGE_DETECTION)){
		   				progress.updateBarText("Segment channel " + channelIDs [c] + " with RIDGE DETECTION ...");
		   				try {
		   					procImp = segmentUsingRidgeDetection(tempImp, progress, ridgeSettings [c],
		   							procImp, channelIDs [c], c, tp1);
		   				}catch(Exception e) {
		   					progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": Ridge detection failed."
		   							+ "\nVerify that the Ridge Detection plugin and commons-lang3 are installed."
		   							+ "\nError message: " + e.getMessage()
									+ "\nError localized message: " + e.getLocalizedMessage()
									+ "\nError cause: " + e.getCause() 
									+ "\nDetailed message:"
									+ "\n" + stackTraceToString(e), ProgressReporter.ERROR);
		   					break running;
		   				}
		   				tempImp.changes = false;
		   				tempImp.close();
		   			}else if(ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.NAME.equals(
		   					chosenAlgorithms [c])){
		   				/*
		   				 * A local threshold surface is inherently PER SLICE - the local mean and sd
		   				 * are 2D - so this branch ignores the stack-processing choice.
		   				 */
		   				progress.updateBarText("Phansalkar local threshold for channel "
		   						+ channelIDs [c] + " ...");
		   				tp1.append("Threshold(s) for channel " + channelIDs [c]);
		   				if(phansalkarSettings [c] == null)
		   					phansalkarSettings [c] = ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.initDefault();
		   				ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings ps = phansalkarSettings [c];
		   				String pwarn = ps.rangeWarning();
		   				if(pwarn != null){
		   					progress.notifyMessage("Channel " + channelIDs [c] + ": " + pwarn,
		   							ProgressReporter.NOTIFICATION);
		   					tp1.append("	WARNING	" + pwarn);
		   				}
		   				/*
		   				 * Normalise by the STACK maximum, not the type maximum and not the slice
		   				 * maximum. exp(-q*mean) acts on a [0,1]-normalised image so the divisor changes
		   				 * the result; dividing 16-bit data by 65535 would compress these images into
		   				 * [0, 0.5]. Using the stack maximum rather than each slice's keeps the surfaces
		   				 * of different slices on one scale.
		   				 */
		   				double [] pNorm = ciliaQ_Prep_jnh.local_threshold.Phansalkar.stackRange(tempImp);
		   				tp1.append("	Normalisation range (stack min - max)	" + df3.format(pNorm [0])
		   						+ " - " + df3.format(pNorm [1]));
		   				tp1.append("	Time step #	Slice #	Threshold range");
		   				for(int t = 0; t < procImp.getNFrames(); t++){
		   					progress.updateBarText("Applying segmentation to channel " + channelIDs [c] + " ...");
		   					progress.addToBar(1.0/procImp.getNFrames());
		   					for(int sl = 0; sl < procImp.getNSlices(); sl++){
		   						if(keepAwake)	stayAwake();
		   						int tempIdx = tempImp.getStackIndex(1, sl+1, t+1)-1;
		   						float [] surf = ciliaQ_Prep_jnh.local_threshold.Phansalkar.surface(
		   								tempImp.getStack().getProcessor(tempIdx+1), ps.radius,
		   								pNorm [0], pNorm [1], ps.k, ps.r, ps.p, ps.q);
		   						double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
		   						for(int i = 0; i < surf.length; i++){
		   							if(surf [i] < mn)	mn = surf [i];
		   							if(surf [i] > mx)	mx = surf [i];
		   						}
		   						tp1.append("	" + df0.format(t+1) + "	" + df0.format(sl+1)
		   								+ "	" + df3.format(mn) + " - " + df3.format(mx));
		   						this.segmentImageWithSurface(procImp, surf, tempImp.getWidth(), 0.0,
		   								procImp.getStackIndex(channelIDs [c], sl+1, t+1)-1,
		   								tempImp, tempIdx);
		   					}
		   				}
		   				tempImp.changes = false;
		   				tempImp.close();
		   			}else if(ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.isFractionMethod(
		   					chosenAlgorithms [c])){
		   				/*
		   				 * Separate from the plain-threshold branch below because the LOCAL variant
		   				 * produces a threshold SURFACE rather than one number, so it cannot travel
		   				 * through getThresholds' double[]. The GLOBAL variant could, but keeping both
		   				 * here means the two share one code path and one report format.
		   				 */
		   				boolean localMethod = ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.LOCAL
		   						.equals(chosenAlgorithms [c]);
		   				progress.updateBarText("Determine fraction-of-range threshold for channel "
		   						+ channelIDs [c] + " ...");
		   				tp1.append("Threshold(s) for channel " + channelIDs [c]);
		   				if(fractionSettings [c] == null)
		   					fractionSettings [c] = ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.initDefault();
		   				String fwarn = fractionSettings [c].rangeWarning(localMethod);
		   				if(fwarn != null){
		   					progress.notifyMessage("Channel " + channelIDs [c] + ": " + fwarn,
		   							ProgressReporter.NOTIFICATION);
		   					tp1.append("	WARNING	" + fwarn);
		   				}
		   				ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result [][] fres =
		   						getFractionThresholds(tempImp, fractionSettings [c], localMethod,
		   								chosenStackMethods [c], separateTimesteps [c], progress);
		   				boolean fPerSlice = chosenStackMethods [c].equals(STACK_PER_SLICE);
		   				if(fPerSlice)					tp1.append("	Time step #	Slice #	Threshold");
		   				else if(separateTimesteps [c])	tp1.append("	Time step #	Threshold");
		   				for(int t = 0; t < procImp.getNFrames(); t++){
		   					progress.updateBarText("Applying segmentation to channel " + channelIDs [c] + " ...");
		   					progress.addToBar(0.2/procImp.getNFrames());
		   					ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result [] row =
		   							fres [fres.length == 1 ? 0 : Math.min(t, fres.length-1)];
		   					if(!fPerSlice && separateTimesteps [c]){
		   						ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result r0 = row [0];
		   						tp1.append("	" + df0.format(t+1) + "	" + df3.format(r0.global)
		   								+ (localMethod ? "	(local " + df3.format(r0.surfaceMin) + " - "
		   										+ df3.format(r0.surfaceMax) + ")" : ""));
		   					}else if(!fPerSlice && t == 0){
		   						tp1.append("	All timesteps:	" + df3.format(row [0].global));
		   						if(localMethod)
		   							tp1.append("	Local threshold surface range:	"
		   									+ df3.format(row [0].surfaceMin) + " - "
		   									+ df3.format(row [0].surfaceMax));
		   					}
		   					for(int sl = 0; sl < procImp.getNSlices(); sl++){
		   						ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result r =
		   								row [row.length == 1 ? 0 : Math.min(sl, row.length-1)];
		   						if(fPerSlice){
		   							tp1.append("	" + df0.format(t+1) + "	" + df0.format(sl+1)
		   									+ "	" + df3.format(r.global)
		   									+ (localMethod ? "	(local " + df3.format(r.surfaceMin) + " - "
		   											+ df3.format(r.surfaceMax) + ")" : ""));
		   						}
		   						this.segmentImageWithSurface(procImp, r,
		   								procImp.getStackIndex(channelIDs [c], sl+1, t+1)-1,
		   								tempImp, tempImp.getStackIndex(1, sl+1, t+1)-1);
		   					}
		   				}
		   				tempImp.changes = false;
		   				tempImp.close();
		   			}else {
		   				progress.updateBarText("Determine threshold for channel " + channelIDs [c] + " ...");				   			
			   			tp1.append("Threshold(s) for channel " + channelIDs [c]);
			   			/*	Thresholds are [time step][slice]. Every non-per-slice mode returns a single
			   			 *	slice entry, which thresholdAt broadcasts, so the per-slice option shares this
			   			 *	one apply loop instead of getting a parallel one. */
			   			double [][] thr2;
			   			boolean perSlice = chosenStackMethods [c].equals(STACK_PER_SLICE);
			   			if(!chosenAlgorithms [c].equals("CUSTOM threshold")) {
			   				thr2 = getThresholdsPerSlice(tempImp, chosenStackMethods [c], chosenAlgorithms [c], separateTimesteps [c], progress);
			   			}else {
			   				thr2 = new double [procImp.getNFrames()][1];
			   				for(int t = 0; t < thr2.length; t++)	thr2 [t][0] = customThr [c];
			   			}
			   			threshold = thr2 [0][0];
			   			if(perSlice && !chosenAlgorithms [c].equals("CUSTOM threshold")){
			   				tp1.append("	Time step #	Slice #	Threshold");
			   			}else if(chosenAlgorithms [c].equals("CUSTOM threshold") || !separateTimesteps [c]){
			   				tp1.append("	All timesteps:	" + df3.format(threshold));
			   			}else{
			   				tp1.append("	Time step #	Threshold");
			   			}
			   			for(int t = 0; t < procImp.getNFrames(); t++){
			   				progress.updateBarText("Applying segmentation to channel " + channelIDs [c] + " ...");
			   				progress.addToBar(0.2/procImp.getNFrames());
			   				if(!perSlice && separateTimesteps [c] && !chosenAlgorithms [c].equals("CUSTOM threshold")){
			   					threshold = thresholdAt(thr2, t, 0);
			   					tp1.append("	" + df0.format(t+1) + "	" + df3.format(threshold));
			   				}
			   				for(int s = 0; s < procImp.getNSlices(); s++){
			   					double thrHere = thresholdAt(thr2, t, s);
			   					if(perSlice && !chosenAlgorithms [c].equals("CUSTOM threshold")){
			   						tp1.append("	" + df0.format(t+1) + "	" + df0.format(s+1)
			   								+ "	" + df3.format(thrHere));
			   					}
					   			this.segmentImage(procImp, thrHere, procImp.getStackIndex(channelIDs [c], s+1, t+1)-1, 
					   					tempImp, tempImp.getStackIndex(1, s+1, t+1)-1);		   					
			   				}
			   			}
			   			tempImp.changes = false;
			   			tempImp.close();
		   			}
		   		}
		   	}
		   	
		   	/*
		   	 * One promotion for all ridge channels, then write every stashed label set. Deferred to
		   	 * here so a promotion cannot disturb another channel's segmentation or display state.
		   	 */
		   	procImp = finishPendingLabels(procImp, progress, tp1);

		   	/*
			 * Mask metrics and the "the threshold removed nothing" check. AFTER
		   	 * finishPendingLabels, because the label-producing methods have written nothing before it and
		   	 * would all measure as empty.
		   	 */
		   	reportMaskMetrics(procImp, imp, tp1, progress, task, tasks);

		   	if(addC > 0){
		   		tempImp = IJ.createHyperStack(imp.getTitle() + " cq", imp.getWidth(), imp.getHeight(), 
		   				imp.getNChannels()+addC,
		   				imp.getNSlices(), imp.getNFrames(), procImp.getBitDepth());
		   		tempImp.setCalibration(imp.getCalibration());
		   		tempImp.setDisplayMode(IJ.COMPOSITE);
		   		
		   		int indexOld, indexNew;
		   		int cNew = 0;
		   		for(int x = 0; x < procImp.getWidth(); x++){
		   			for(int y = 0; y < procImp.getHeight(); y++){
		   				for(int s = 0; s < procImp.getNSlices(); s++){
		   					for(int f = 0; f < procImp.getNFrames(); f++){
	   							cNew = 0;
		   						for(int c = 0; c < procImp.getNChannels(); c++){
		   							indexOld = procImp.getStackIndex(c+1, s+1, f+1)-1;
			   						indexNew = tempImp.getStackIndex(c+cNew+1, s+1, f+1)-1;
			   						tempImp.getStack().setVoxel(x, y, indexNew, procImp.getStack().getVoxel(x, y, indexOld));			   						
		   							for(int i = 0; i < channelIDs.length; i++){
		   								if(c+1 == channelIDs [i] && includeDuplicateChannel [i]){
					   						cNew ++;
					   						indexOld = imp.getStackIndex(c+1, s+1, f+1)-1;
					   						indexNew = tempImp.getStackIndex(c+cNew+1, s+1, f+1)-1;
					   						tempImp.getStack().setVoxel(x, y, indexNew, imp.getStack().getVoxel(x, y, indexOld));
			   							}
		   							}
		   						}
		   					}					
		   				}
		   			}
		   		}
		   		
		   		LUT [] originalLuts = new LUT [imp.getNChannels()];
			   	for(int c = 0; c < imp.getNChannels(); c++){
			   		imp.setC(c+1);
			   		originalLuts[c] = imp.getChannelProcessor().getLut();
			   	}
			   	
			   	/*
			   	 * Capture the LUT each procImp channel carries AFTER finishPendingLabels: label-producing
			   	 * channels (ridge, expansion) now hold a Glasbey LUT, which must survive the reassembly
			   	 * below - otherwise setLuts overwrites it with the original (e.g. blue) source LUT and the
			   	 * labels become unreadable. isLabelChannel marks which channels those are.
			   	 */
			   	LUT [] procLuts = new LUT [procImp.getNChannels()];
			   	boolean [] isLabelChannel = new boolean [procImp.getNChannels()];
			   	for(int c = 0; c < procImp.getNChannels(); c++){
			   		if(procImp.isComposite()){
			   			procLuts [c] = ((CompositeImage) procImp).getChannelLut(c+1);
			   		}else{
			   			procImp.setC(c+1);
			   			procLuts [c] = procImp.getChannelProcessor().getLut();
			   		}
			   		for(int k = 0; k < channelIDs.length; k++){
			   			if(channelIDs [k] == c+1 && (RIDGE_DETECTION.equals(chosenAlgorithms [k])
			   					|| EXPANSION_SEEDS.equals(chosenAlgorithms [k]))){
			   				isLabelChannel [c] = true;
			   				break;
			   			}
			   		}
			   	}
			   	
			   	LUT [] newLuts = new LUT [tempImp.getNChannels()];
			   	
			   	// 1-based ci channel where each procImp channel lands, recorded as we build so the display
			   	// range can be re-applied to label channels without re-deriving the offset (which drifts).
			   	int [] ciChannelForProcChannel = new int [procImp.getNChannels()];
			   	
			   	tp1.append("Channels in output image:");
			   	cNew = 0;
			   	String copyStr;
			   	boolean search;
		   		for(int c = 0; c < procImp.getNChannels(); c++){
		   			if(keepAwake) {
						stayAwake();
					}
		   			// Label channels keep their Glasbey LUT (from procImp); others keep the source LUT.
		   			newLuts [c+cNew] = isLabelChannel [c] ? procLuts [c] : originalLuts [c];
		   			ciChannelForProcChannel [c] = c + cNew + 1;	// 1-based position of this channel in ci
		   			search = false;
					for(int i = 0; i < channelIDs.length; i++){
						if(c+1 == channelIDs [i]){
   							search = true;
   							break;
						}
					}
					if(search){
						tp1.append("Channel " + (c+1+cNew) + ":	" + "previous channel " + (c+1) + " (segmented)");
						for(int s = 0; s < procImp.getNSlices(); s++){
		   					for(int f = 0; f < procImp.getNFrames(); f++){
		   						indexOld = imp.getStackIndex(c+1, s+1, f+1)-1;
			   					indexNew = tempImp.getStackIndex(c+cNew+1, s+1, f+1)-1;
			   					try{
			   						String label = imp.getStack().getSliceLabel(indexOld+1);
			   						if(label == null || label.isEmpty()){
				   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
				   							+  " T" + (f+1) + "/" + procImp.getNFrames();
				   					}else{
				   						copyStr = label;
				   					}
			   					}catch(Exception e){
			   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
		   							+  " T" + (f+1) + "/" + procImp.getNFrames();
			   					}				   					
			   					tempImp.getStack().setSliceLabel("segm " + copyStr, indexNew+1);
		   					}
						}
						
						for(int i = 0; i < channelIDs.length; i++){
							if(c+1 == channelIDs [i] && includeDuplicateChannel [i]){
								cNew ++;
   								newLuts [c+cNew] = originalLuts [c];
   								tp1.append("Channel " + (c+1+cNew) + ":	" + "previous channel " + (c+1) + "");
   								
   								for(int s = 0; s < procImp.getNSlices(); s++){
   				   					for(int f = 0; f < procImp.getNFrames(); f++){
	   				   					indexOld = imp.getStackIndex(c+1, s+1, f+1)-1;
					   					indexNew = tempImp.getStackIndex(c+cNew+1, s+1, f+1)-1;
					   					try{
					   						String label = imp.getStack().getSliceLabel(indexOld+1);
					   						if(label == null || label.isEmpty()){
						   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
					   							+  " T" + (f+1) + "/" + procImp.getNFrames();
						   					}else{
						   						copyStr = label;
						   					}
					   					}catch(Exception e){
					   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
				   							+  " T" + (f+1) + "/" + procImp.getNFrames();
					   					}					   					
					   					tempImp.getStack().setSliceLabel(copyStr, indexNew+1);
   				   					}
   								}   								
							}
						}
					}else{
						tp1.append("Channel " + (c+1+cNew) + ":	" + "previous channel " + (c+1) + "");
						for(int s = 0; s < procImp.getNSlices(); s++){
		   					for(int f = 0; f < procImp.getNFrames(); f++){
		   						indexOld = imp.getStackIndex(c+1, s+1, f+1)-1;
			   					indexNew = tempImp.getStackIndex(c+cNew+1, s+1, f+1)-1;
			   					try{
			   						String label = imp.getStack().getSliceLabel(indexOld+1);
			   						if(label == null || label.isEmpty()){
				   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
			   							+  " T" + (f+1) + "/" + procImp.getNFrames();
				   					}else{
				   						copyStr = label;
				   					}
			   					}catch(Exception e){
			   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
		   							+  " T" + (f+1) + "/" + procImp.getNFrames();
			   					}			   					
			   					tempImp.getStack().setSliceLabel(copyStr, indexNew+1);
		   					}
						}
					}
				}
		   		
		   		if(keepAwake) {
					stayAwake();
				}
		   		
	   			CompositeImage ci = (CompositeImage) tempImp;
	   			ci.setDisplayMode(IJ.COMPOSITE);
	   			ci.setLuts(newLuts);
	   			
	   			/*
	   			 * Re-apply the label channels' display range on the reassembled image: setLuts above does
	   			 * not carry over the 0..nLabels range set on procImp, so without this the Glasbey colours
	   			 * sit near-black against the full bit-depth range. Uses the exact landing position recorded
	   			 * during reassembly (ciChannelForProcChannel), so it cannot drift from the build loop.
	   			 */
	   			for(int c = 0; c < procImp.getNChannels(); c++){
	   				if(!isLabelChannel [c])	continue;
	   				int max = 1;
	   				for(int t = 0; t < procImp.getNFrames(); t++){
	   					for(int s = 0; s < procImp.getNSlices(); s++){
	   						int idx = procImp.getStackIndex(c+1, s+1, t+1);
	   						ij.process.ImageProcessor lp = procImp.getStack().getProcessor(idx);
	   						for(int i = 0; i < lp.getPixelCount(); i++){
	   							int v = (int) lp.getf(i);
	   							if(v > max)	max = v;
	   						}
	   					}
	   				}
	   				ci.setC(ciChannelForProcChannel [c]);	// exact position recorded during reassembly
	   				ci.setDisplayRange(0, max);
	   			}
	   			
				IJ.saveAsTiff(ci, filePrefix + ".tif");
				procImp.changes = false;
		   		procImp.close();
		   		tempImp.changes = false;
				tempImp.close();
				ci.changes = false;
				ci.close();	   			
		   	}else{
				IJ.saveAsTiff(procImp, filePrefix + ".tif");	
				procImp.changes = false;
				procImp.close();
		   	}
		   	
		   	addFooter(tp1, startDate);				
			writeTextFile(filePrefix + ".txt", tp1.getText()); //tp1.saveAs(filePrefix + ".txt"); Switched to java-based writing to avoid macro notion

			progress.updateBarText("Finished ...");
		   
			
			System.gc();
		/******************************************************************
		*** 							Finish							***	
		*******************************************************************/			
			{
				imp.unlock();	
				if(selectedTaskVariant.equals(taskVariant[1]) || isVirtual){
					imp.changes = false;
					imp.close();
				}
				processingDone = true;
				break running;
			}				
		}	
		progress.updateBarText("finished!");
		progress.setBar(1.0);
		progress.moveTask(task);
	}}finally{
		Recorder.record = recordPref;
		Prefs.blackBackground = backgroundPref;		
	}	
} finally {
	//set safeguard for dual runs off
    runInProgress = false;
}
}

/**
 * Import settings from existing file
 * Updated for V0.2.0 to handle headless loading
 */
private boolean importSettings() {
	File settingsFile = null;	
	String macroOptions = IJ.macroRunning() ? Macro.getOptions() : null;
	String macroPath = macroValue(macroOptions, "settings");	
	if(macroPath != null){
		settingsFile = new File(macroPath);
		settingsFile = redirectTifToTxt(settingsFile); // Corrects common mistake: user points at the _CQP.tif output instead of the _CQP.txt metadata.
		if(!settingsFile.exists() || !settingsFile.canRead()){
			IJ.error(PLUGIN_NAME + ": cannot read the settings file '" + settingsFile + "'.");
			return false;
		}
		IJ.log(PLUGIN_NAME + ": loading preferences from " + settingsFile);
	}else{
		if(GraphicsEnvironment.isHeadless()){
			IJ.error(PLUGIN_NAME + ": cannot open a file dialog while headless."
					+ " Pass settings=[/path/to/..._CQP.txt] in the macro options.");
			return false;
		}
		java.awt.FileDialog fd = new java.awt.FileDialog((Frame) null, "Select CQP file to load preferences!");
		fd.setDirectory(System.getProperty("user.dir", "."));
		fd.setMultipleMode(false);
		fd.setMode(FileDialog.LOAD);
		fd.setVisible(true);
		// getFiles() is empty when the user cancels - indexing it threw before.
		if(fd.getFiles() == null || fd.getFiles().length == 0){
			return false;
		}
		settingsFile = fd.getFiles()[0];
		settingsFile = redirectTifToTxt(settingsFile); // Corrects common mistake: user points at the _CQP.tif output instead of the _CQP.txt metadata.
		if(!settingsFile.exists() || !settingsFile.canRead()){
			IJ.error(PLUGIN_NAME + ": cannot read the settings file '" + settingsFile + "'.");
			return false;
		}
		IJ.log(PLUGIN_NAME + ": loading preferences from " + settingsFile);
	}

	//read general settings
	IJ.log("READING PREFERENCES:");
	nChannels = 0;
	try {
		FileReader fr = new FileReader(settingsFile);
		BufferedReader br = new BufferedReader(fr);
		String line = "";							
		reading: while(true){
			try{
				line = br.readLine();	
				if(line == null){
					break reading;
				}
			}catch(Exception e){
				break reading;
			}
			
			if(line.contains("Segmentation style")) {
				for(int i = 0; i < intensityVariant.length; i ++) {
					if(line.contains(intensityVariant [i])) {
						chosenImageStyle = intensityVariant [i];
					}
				}
				if(chosenImageStyle.equals(intensityVariant [0])){
					keepIntensities = true;
				}
				IJ.log("Segmentation style = " + chosenImageStyle);
			}
			
			if(line.contains("Segmented channel")) {
				nChannels ++;
			}
		}					
		br.close();
		fr.close();
	}catch (IOException e) {
		IJ.error("Problem with loading preferences");
		e.printStackTrace();
		return false;
	}
	
	
	// initialize for nr of channels (switch to new void method in V0.2.0)
	initChannelArrays(nChannels);
	
	//read individual channel settings
	boolean channelReading = false;
	int actualC = -1;
	String tempString;
	try {
		FileReader fr = new FileReader(settingsFile);
		BufferedReader br = new BufferedReader(fr);
		String line = "";							
		reading: while(true){
			try{
				line = br.readLine();	
				if(line == null){
					break reading;
				}
			}catch(Exception e){
				break reading;
			}
			
			if(line.contains("Segmented channel")) {
				channelReading = true;
				actualC++;
			}else if(isUnindentedHeader(line) && (
					line.startsWith("Threshold(s) for channel")
					|| line.startsWith("Ridge detection for channel")
					|| line.startsWith("Spot detection for channel")
					|| line.startsWith("Expansion from seeds for channel")
					|| line.startsWith("Mask metrics per segmented channel"))) {
				/*
				 * The PROCESSING section begins here. Its headers are written at column 0 (no leading
				 * tab/space), whereas every per-channel SETTINGS line is indented. Requiring column 0
				 * (via startsWith on a line with no leading whitespace) means these sentinels fire only
				 * on real section headers, not on an indented detail line that merely contains the same
				 * text - which closes the latent trap that left channelReading true through the log.
				 */
				channelReading = false;
			}
			
			if(channelReading){
				if(line.contains("Channel Nr:")){
					tempString = line.substring(line.lastIndexOf("	")+1);
					if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
					channelIDs [actualC] = Integer.parseInt(tempString);
					IJ.log("C" + actualC + ": channel nr = " + channelIDs [actualC]);
				}
				if(line.contains("Channel duplicated to include a copy of the channel that is not segmented.")){
					includeDuplicateChannel [actualC] = true;
					IJ.log("C" + actualC + ": duplicate C");
				}
				if(line.contains("Median filter before blurring")){ // New in V0.2.1
					medianFilter [actualC] = true;
					tempString = line.substring(line.lastIndexOf("	")+1);
					if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
					medianRadius [actualC] = Double.parseDouble(tempString);
					IJ.log("C" + actualC + ": median rad = " + medianRadius [actualC]);
				}
				if(line.contains("Gaussian blur before background removal")){
					preBlur [actualC] = true;
					tempString = line.substring(line.lastIndexOf("	")+1);
					if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
					preBlurRadius [actualC] = Double.parseDouble(tempString);
					IJ.log("C" + actualC + ": pre-blur rad = " + preBlurRadius [actualC]);
				}
				if(line.contains("Subtract Background:")){
					subtractBackground [actualC] = true;
					tempString = line.substring(line.lastIndexOf("	")+1);
					if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
					subtractBGRadius [actualC] = Double.parseDouble(tempString);
					IJ.log("C" + actualC + ": sbg rad = " + subtractBGRadius [actualC]);
				}
				if(line.contains("Divide By Background:")){
					divideBackground [actualC] = true;
					tempString = line.substring(line.lastIndexOf("	")+1);
					if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
					divideBGRadius [actualC] = Double.parseDouble(tempString);
					IJ.log("C" + actualC + ": dbg rad = " + divideBGRadius [actualC]);
				}
				if(line.contains("Additional blur with Gaussian")){
					additionalBlur [actualC] = true;
					tempString = line.substring(line.lastIndexOf("	")+1);
					if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
					additionalBlurRadius [actualC] = Double.parseDouble(tempString);
					IJ.log("C" + actualC + ": blur rad = " + additionalBlurRadius [actualC]);
				}
				if(line.contains("Every time step separately segmented.")) {
					separateTimesteps [actualC]= true;
					IJ.log("C" + actualC + ": time steps separately");
				}
				
				if(line.contains("Segmentation method:")){
					if(line.contains("Canny 3D Thresholder")) {
						chosenAlgorithms [actualC] = algorithm [17];
						IJ.log("C" + actualC + ": segment with " + chosenAlgorithms [actualC]);
						
						cannySettings [actualC] = ProcessSettings.initDefault();
						line = br.readLine();
						if(line == null){IJ.error("Reading problem with Gauss Sigma."); break reading;}
						if(!line.contains("Gauss sigma:")) {
							IJ.error("Gauss not found in Canny settings - no preferences loading!");
							return false;
						}
						tempString = line.substring(line.lastIndexOf("	")+1);
						if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
						cannySettings [actualC].setGaussSigma(Double.parseDouble(tempString));
						IJ.log("C" + actualC + ": Gauss " + cannySettings [actualC].getGaussSigma());
						
						line = br.readLine();
						if(line == null){IJ.error("Reading problem with Canny alpha."); break reading;}
						if(!line.contains("Canny alpha:")) {
							IJ.error("Alpha not found in Canny settings - no preferences loading!");
							return false;
						}
						tempString = line.substring(line.lastIndexOf("	")+1);
						if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
						cannySettings [actualC].setCannyAlpha(Double.parseDouble(tempString));
						IJ.log("C" + actualC + ": Alpha " + cannySettings [actualC].getCannyAlpha());
						
						line = br.readLine();
						if(line == null){IJ.error("Reading problem with low threshold method."); break reading;}
						if(!line.contains("Low threshold method")) {
							IJ.error("LowThr method not found in Canny settings - no preferences loading!");
							return false;
						}
						
						//Bug fixed in V0.2.0 (IsoData and IJ_IsoData could be confused with prior method)
						String saved = line.substring(line.lastIndexOf("	")+1).trim();
						for(int a = 0; a < ProcessSettings.thrAlgorithms.length; a++) {
							if(saved.equals(ProcessSettings.thrAlgorithms[a])) {
								cannySettings [actualC].setLowThresholdAlgorithm(ProcessSettings.thrAlgorithms [a]);
								break;
							}
						}
						
						if(cannySettings [actualC].getLowThresholdAlgorithm().equals(ProcessSettings.thrAlgorithms[0])) {
							line = br.readLine();
							if(line == null){IJ.error("Reading problem with manually selected low threshold."); break reading;}
							if(!line.contains("Manually selected low threshold:")){
								IJ.error("Could not find manual lowThr in Canny settings - no preferences loaded!");
								return false;
							}
							tempString = line.substring(line.lastIndexOf("	")+1);
							if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
							cannySettings [actualC].setLowThr(Double.parseDouble(tempString));
						}
						IJ.log("C" + actualC + ": low Thr " + cannySettings [actualC].getLowThresholdAlgorithm());
						
						line = br.readLine();
						if(line == null){IJ.error("Reading problem with high threshold method."); break reading;}
						if(!line.contains("High threshold method")) {
							IJ.error("HiThr method not found in Canny settings - no preferences loading!");
							return false;
						}

						//Bug fixed in V0.2.0 (IsoData and IJ_IsoData could be confused with prior method)
						saved = line.substring(line.lastIndexOf("	")+1).trim();
						for(int a = 0; a < ProcessSettings.thrAlgorithms.length; a++) {
							if(saved.equals(ProcessSettings.thrAlgorithms[a])) {
								cannySettings [actualC].setHighThresholdAlgorithm(ProcessSettings.thrAlgorithms [a]);
								break;
							}
						}
						
						if(cannySettings [actualC].getHighThresholdAlgorithm().equals(ProcessSettings.thrAlgorithms[0])) {
							line = br.readLine();
							if(line == null){IJ.error("Reading problem with manually selected high threshold."); break reading;}
							if(!line.contains("Manually selected high threshold:")){
								IJ.error("Could not find manual highThr in Canny settings - no preferences loaded!");
								return false;
							}
							tempString = line.substring(line.lastIndexOf("	")+1);
							if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
							cannySettings [actualC].setHighThr(Double.parseDouble(tempString));
						}
						IJ.log("C" + actualC + ": High Thr " + cannySettings [actualC].getHighThresholdAlgorithm());
						
					}else if(line.contains("HYSTERESIS threshold")) {
						chosenAlgorithms [actualC] = algorithm [19];
						IJ.log("C" + actualC + ": segment with " + chosenAlgorithms [actualC]);	
						
						line = br.readLine();
						if(line == null){IJ.error("Reading problem"); break reading;}
						if(!line.contains("Low threshold method")) {
							IJ.error("LowThr method not found in Hysteresis settings - no preferences loading!");
							return false;
						}
						
						//Bug fixed in V0.2.0 (exact match; IsoData/IJ_IsoData substring safety)
						String saved = line.substring(line.lastIndexOf("	")+1).trim();
						for(int a = 0; a < hystAlgorithms.length; a++) {
							if(saved.equals(hystAlgorithms[a])) {
								chosenLowAlg [actualC] = (hystAlgorithms [a]);
								break;
							}
						}
						
						IJ.log("C" + actualC + ": Low Thr method" + chosenLowAlg [actualC]);
						if(chosenLowAlg [actualC].equals(hystAlgorithms[0])) {
							line = br.readLine();
							if(line == null){IJ.error("Reading problem with manually selected low threshold."); break reading;}
							if(!line.contains("Manually selected low threshold:")){
								IJ.error("Could not find manual lowThr in Hysteresis settings - no preferences loaded!");
								return false;
							}
							tempString = line.substring(line.lastIndexOf("	")+1);
							if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
							customLowThr [actualC] = (Double.parseDouble(tempString));
							IJ.log("C" + actualC + ": Low Thr " + customLowThr [actualC]);
						}
						
						line = br.readLine();
						if(line == null){IJ.error("Reading problem with high threshold method."); break reading;}
						if(!line.contains("High threshold method")) {
							IJ.error("HiThr method not found in Hysteresis settings - no preferences loading!");
							return false;
						}
						
						//Bug fixed in V0.2.0 (exact match; IsoData/IJ_IsoData substring safety)
						saved = line.substring(line.lastIndexOf("	")+1).trim();
						for(int a = 0; a < hystAlgorithms.length; a++) {
							if(saved.equals(hystAlgorithms[a])) {
								chosenHighAlg [actualC] = (hystAlgorithms [a]);
								break;
							}
						}
						
						IJ.log("C" + actualC + ": High Thr method" + chosenHighAlg [actualC]);
						if(chosenHighAlg [actualC].equals(hystAlgorithms[0])) {
							line = br.readLine();
							if(line == null){IJ.error("Reading problem with manually selected high threshold."); break reading;}
							if(!line.contains("Manually selected high threshold:")){
								IJ.error("Could not find manual lowThr in Hysteresis settings - no preferences loaded!");
								return false;
							}
							tempString = line.substring(line.lastIndexOf("	")+1);
							if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
							customHighThr [actualC] = (Double.parseDouble(tempString));
							IJ.log("C" + actualC + ": High Thr " + customHighThr [actualC]);
						}
						
						if(chosenHighAlg [actualC].equals(hystAlgorithms[0]) && chosenLowAlg [actualC].equals(hystAlgorithms[0])){
							
						}else {
							line = br.readLine();	
							if(line == null){IJ.error("Reading problem with stack method"); break reading;}
							String smMatch = matchStackMethod(line);		// D6d: alias-aware
							if(smMatch != null)	chosenStackMethods [actualC] = smMatch;
							IJ.log("C" + actualC + ": stack m " + chosenStackMethods [actualC]);	
						}
					}else if(line.contains("CUSTOM threshold")) {
						chosenAlgorithms [actualC] = algorithm [18];
						line = br.readLine();
						if(line == null){IJ.error("Reading problem with CUSTOM threshold value"); break reading;}
						if(!line.contains("Custom threshold value:")){
							IJ.error("Could not find custom threshold value in settings - no preferences loaded!");
							return false;
						}
						tempString = line.substring(line.lastIndexOf("	")+1);
						if(tempString.contains(",") && !tempString.contains("."))	tempString = tempString.replace(",", ".");
						customThr [actualC] = Double.parseDouble(tempString);
						IJ.log("C" + actualC + ": custom thr" + customThr [actualC]);
					}else if(line.contains("applying intensity threshold based ")) {
						//Bug fixed in V0.2.0 (exact match; IsoData/IJ_IsoData substring safety)
						int s = line.indexOf("based on the ");
						int e = line.indexOf(" threshold algorithm");
						if(s >= 0 && e > s){
							String savedAlg = line.substring(s + "based on the ".length(), e).trim();
							for(int a = 0; a < algorithm.length; a++) {
								if(savedAlg.equals(algorithm[a])) {
									chosenAlgorithms [actualC] = algorithm [a];
									break;
								}
							}
						}
						IJ.log("C" + actualC + ": segment with " + chosenAlgorithms [actualC]);	
						
						line = br.readLine();	
						if(line == null){IJ.error("Reading problem with stack method."); break reading;}
						String smMatch2 = matchStackMethod(line);
						if(smMatch2 != null)	chosenStackMethods [actualC] = smMatch2;
						IJ.log("C" + actualC + ": stack m " + chosenStackMethods [actualC]);
					}else if(line.contains(ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.NAME)) {
						chosenAlgorithms [actualC] = ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.NAME;
						IJ.log("C" + actualC + ": segment with " + chosenAlgorithms [actualC]);
						phansalkarSettings [actualC] =
								ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.initDefault();
						java.util.HashMap<String,String> kv = readIndentedBlock(br);
						ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings ps = phansalkarSettings [actualC];
						ps.radius = blockDouble(kv, "Radius", ps.radius);
						ps.k = blockDouble(kv, "Constant k", ps.k);
						ps.r = blockDouble(kv, "Constant r", ps.r);
						ps.p = blockDouble(kv, "Constant p", ps.p);
						ps.q = blockDouble(kv, "Constant q", ps.q);
						IJ.log("C" + actualC + ": phansalkar radius=" + ps.radius + " k=" + ps.k);
					}else if(line.contains(ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.LOCAL)
							|| line.contains(ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.GLOBAL)) {
						/*	LOCAL IS TESTED FIRST because its name CONTAINS the global one:
						 *	"LOCAL FRACTION of intensity range" contains "FRACTION of intensity range".
						 *	Testing global first would read every local settings file as global. */
						boolean loc = line.contains(
								ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.LOCAL);
						chosenAlgorithms [actualC] = loc
								? ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.LOCAL
								: ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.GLOBAL;
						IJ.log("C" + actualC + ": segment with " + chosenAlgorithms [actualC]);
						fractionSettings [actualC] =
								ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.initDefault();
						java.util.HashMap<String,String> kv = readIndentedBlock(br);
						ciliaQ_Prep_jnh.fraction_threshold.FractionSettings fs = fractionSettings [actualC];
						if(loc){
							fs.fLocal = blockDouble(kv, "Local fraction f", fs.fLocal);
							fs.tileSize = (int) Math.round(blockDouble(kv, "Tile size", fs.tileSize));
							fs.fGlobal = blockDouble(kv, "Global fraction f", fs.fGlobal);
							fs.signalPercentileLocal = blockDouble(kv, "Signal percentile (per tile)",
									fs.signalPercentileLocal);
						}else{
							fs.fGlobal = blockDouble(kv, "Fraction f", fs.fGlobal);
							fs.signalPercentileGlobal = blockDouble(kv, "Signal percentile",
									fs.signalPercentileGlobal);
						}
						fs.bgPercentile = blockDouble(kv, "Background percentile", fs.bgPercentile);
						IJ.log("C" + actualC + ": fraction f=" + (loc ? fs.fLocal : fs.fGlobal)
								+ " tile=" + fs.tileSize + " bgP=" + fs.bgPercentile);
					}else if(line.contains(RIDGE_DETECTION)) {
						chosenAlgorithms [actualC] = RIDGE_DETECTION;
						IJ.log("C" + actualC + ": segment with " + chosenAlgorithms [actualC]);
						ridgeSettings [actualC] = ciliaQ_Prep_jnh.ridge_detection.RidgeSettings.initDefault();
						java.util.HashMap<String,String> kv = readIndentedBlock(br);
						ciliaQ_Prep_jnh.ridge_detection.RidgeSettings rs = ridgeSettings [actualC];
						rs.lineWidth = blockDouble(kv, "Expected filament width", rs.lineWidth);
						rs.sensitivity = blockDouble(kv, "Contrast sensitivity", rs.sensitivity);
						rs.minLength = blockDouble(kv, "Minimum filament length", rs.minLength);
						String maxL = blockValue(kv, "Maximum filament length");
						rs.maxLength = (maxL == null || maxL.contains("unlimited")) ? 0.0
								: parseNum(maxL, rs.maxLength);
						rs.resolveCrossings = blockBoolean(kv, "Resolve crossings", rs.resolveCrossings);
						rs.darkLine = blockBoolean(kv, "Dark line", rs.darkLine);
						/*	These three are Steger detector switches that the dialog exposes. They were
						 *	lost on every round trip until report() stopped combining them into one
						 *	"a / b / c" line - see RidgeSettings.report(). */
						rs.correctPosition = blockBoolean(kv, "Correct position", rs.correctPosition);
						rs.estimateWidth = blockBoolean(kv, "Estimate width", rs.estimateWidth);
						rs.extendLine = blockBoolean(kv, "Extend line", rs.extendLine);
						rs.advancedOverride = blockBoolean(kv, "Advanced values overridden by user", false);
						/*	The Steger values are only present when they were overridden or already
						 *	derived; when derived they were written for a PREVIOUS image, so they must
						 *	not be restored - deriveFrom will recompute them for the new one. */
						if(rs.advancedOverride){
							rs.sigma = blockDouble(kv, "Sigma", rs.sigma);
							rs.upperThreshold = blockDouble(kv, "Upper threshold", rs.upperThreshold);
							rs.lowerThreshold = blockDouble(kv, "Lower threshold", rs.lowerThreshold);
						}
						IJ.log("C" + actualC + ": ridge w=" + rs.lineWidth + " sens=" + rs.sensitivity
								+ " minLen=" + rs.minLength + " override=" + rs.advancedOverride);
					}else if(line.contains("EXPANSION from seeds")) {
						chosenAlgorithms [actualC] = EXPANSION_SEEDS;
						IJ.log("C" + actualC + ": segment with " + chosenAlgorithms [actualC]);
						expansionSettings [actualC] =
								ciliaQ_Prep_jnh.expansion.ExpansionSettings.initDefault();
						java.util.HashMap<String,String> kv = readIndentedBlock(br);
						ciliaQ_Prep_jnh.expansion.ExpansionSettings es = expansionSettings [actualC];
						String src = blockValue(kv, "Seed source");
						if(src != null){
							for(int a = 0; a < ciliaQ_Prep_jnh.expansion.ExpansionSettings.seedSources.length; a++){
								if(src.equals(ciliaQ_Prep_jnh.expansion.ExpansionSettings.seedSources [a])){
									es.seedSource = ciliaQ_Prep_jnh.expansion.ExpansionSettings.seedSources [a];
									break;
								}
							}
						}
						String lowAlg = blockValue(kv, "Threshold for the object extent");
						if(lowAlg != null)	es.lowThrAlgorithm = matchThrAlgorithm(lowAlg, es.lowThrAlgorithm);
						es.customLowThr = blockDouble(kv, "Manually selected threshold", es.customLowThr);
						es.minPieceSize = (int) Math.round(blockDouble(kv,
								"Minimum object size after splitting", es.minPieceSize));
						es.ridgeWidth = blockDouble(kv, "Ridge seed - expected filament width", es.ridgeWidth);
						es.ridgeSensitivity = blockDouble(kv, "Ridge seed - contrast sensitivity",
								es.ridgeSensitivity);
						String seedAlg = blockValue(kv, "High core seed - seed threshold");
						if(seedAlg != null)	es.seedThrAlgorithm = matchThrAlgorithm(seedAlg, es.seedThrAlgorithm);
						es.customSeedThr = blockDouble(kv, "High core seed - manually selected seed threshold",
								es.customSeedThr);
						es.spotDiameter = blockDouble(kv, "Spot seed - expected diameter", es.spotDiameter);
						es.spotSensitivity = blockDouble(kv, "Spot seed - sensitivity", es.spotSensitivity);
						IJ.log("C" + actualC + ": expansion seeds=" + es.seedSource + " low="
								+ es.lowThrAlgorithm + " minPiece=" + es.minPieceSize);
					}else if(line.contains("SPOT DETECTION")) {
						chosenAlgorithms [actualC] = SPOT_DETECTION;
						IJ.log("C" + actualC + ": segment with " + chosenAlgorithms [actualC]);
						spotSettings [actualC] = ciliaQ_Prep_jnh.spot_detection.SpotSettings.initDefault();
						java.util.HashMap<String,String> kv = readIndentedBlock(br);
						ciliaQ_Prep_jnh.spot_detection.SpotSettings ss = spotSettings [actualC];
						ss.diameter = blockDouble(kv, "Expected object diameter", ss.diameter);
						ss.variation = blockDouble(kv, "Diameter variation", ss.variation);
						ss.sensitivity = blockDouble(kv, "Sensitivity", ss.sensitivity);
						ss.scaleRatio = blockDouble(kv, "Scale ratio", ss.scaleRatio);
						ss.autoMinIntensity = blockBoolean(kv, "Auto minimum spot intensity", true);
						if(!ss.autoMinIntensity){
							ss.minIntensity = blockDouble(kv, "Minimum spot intensity", ss.minIntensity);
						}
						IJ.log("C" + actualC + ": spot d=" + ss.diameter + " var=" + ss.variation
								+ " sens=" + ss.sensitivity + " autoMin=" + ss.autoMinIntensity);
					}else{
						/*	Previously an unrecognised method line fell through in silence and the
						 *	channel kept whatever chosenAlgorithms happened to hold. Loading a file
						 *	written by a NEWER Preparator into an older one does exactly this, so say so
						 *	instead of segmenting with the wrong method. */
						IJ.error(PLUGIN_NAME + ": the settings file names a segmentation method this"
								+ " version does not know:\n" + line.trim()
								+ "\nNo preferences were loaded.");
						return false;
					}
				}
			}
			
			if(line.contains("Plug-in version:")) {
				String version = line.substring(line.lastIndexOf("V"));
				IJ.log("Loaded settings stem from a CiliaQ Preparator run with Version " + version + ".");

				// V0.2.0: note when settings come from a different version than this build.
				if(!version.equals("V" + PLUGIN_VERSION)){
					IJ.log(PLUGIN_NAME + ": settings were saved by version " + version
						+ ", this plugin is V" + PLUGIN_VERSION
						+ ". Settings that did not exist in the saved version keep their defaults.");
				}
				
				/**
				 * In Version V0.1.2, bugs in the generic dialog for Canny3D settings have been corrected.
				 * For downwards compatibility, some settings need to be adjusted when reading in an analysis from older versions.
				 * This is done here.
				 */
				if(version.equals("V0.1.1") || version.equals("V0.1.0") || version.equals("V0.0.6") || version.equals("V0.0.5") || version.equals("V0.0.4") || version.equals("V0.0.3") || version.equals("V0.0.2")) {
					IJ.log("Detected that setting loaded stems from an older CiliaQ Preparator version (Version " + version + ")");
					for(int c = 0; c < chosenAlgorithms.length; c++) {
						if(chosenAlgorithms [c].equals(algorithm [17])) {
							String tempLowAlg = cannySettings [c].getLowThresholdAlgorithm();
							String tempHighAlg = cannySettings [c].getHighThresholdAlgorithm();
							
							/*
							 * In versions before V0.1.2, in the Canny settings the algorithm labels were swapped in the generic dialog
							 * Thus, what was entered as indicated left ended up in as indicated right
							 * low thr alg			>		high thr alg
							 * custom value 		>		low thr
							 * high thr alg			>		low thr alg
							 * custom value 		>		high thr
							 * Later in the processing however a low thr alg other than "Custom" would be used as high threshold and vice versa
							 * Before version V0.1.1 import worked fine since loading and writing settings was still correct in these versions.
							 * From version V0.1.2, all labels have been corrected and the bug in the processing has been corrected,
							 * thus, to be compatible with older versions, we need to perform some import corrections.							 *  
							 * */
							if(!tempLowAlg.equals(ProcessSettings.thrAlgorithms[0]) && !tempHighAlg.equals(ProcessSettings.thrAlgorithms[0])) {
								/**
								 * We just need read in algorithms
								 */
								cannySettings [c].setLowThresholdAlgorithm(tempHighAlg);
								cannySettings [c].setHighThresholdAlgorithm(tempLowAlg);
								IJ.log("C" + c + ": low Thr and high Thr methods were swapped to reproduce performance of the older version of CiliaQ (" + version + ") for which the loaded setting was used"
										+ " (for more details see release notes at https://github.com/hansenjn/CiliaQ_Preparator/releases/tag/v0.1.2 ):");
								IJ.log("C" + c + ": adjusted low Thr method:" + cannySettings [c].getLowThresholdAlgorithm());
								IJ.log("C" + c + ": adjusted high Thr method:" + cannySettings [c].getHighThresholdAlgorithm());
							}else if(tempLowAlg.equals(ProcessSettings.thrAlgorithms[0]) && tempHighAlg.equals(ProcessSettings.thrAlgorithms[0])) {
								/**
								 * Nothing needs to be swapped since low and high Thr values were correctly entered and applied
								 */
							}else {
								/**
								 * In this case, one of the threshold values was indicated but not used and instead 0.0 was used, which will likely have completely failed Canny 3D processing.
								 * It is not likely that this ever was used, but to be fully compatible, we will import it correctly.								 * 
								 */
								if(tempLowAlg.equals(ProcessSettings.thrAlgorithms[0]) && !tempHighAlg.equals(ProcessSettings.thrAlgorithms[0])) {
									/** 
									 * Low was set custom and value entered, high was other, value 0.0
									 * Old version consequence: high is custom, high value 0.0, low is SomeAlg, low value someValueButNotStored
									 * Application:
									 * Low thr is some alg and will apply custom alg, which cannot be find - a random conversion to mask will appear
									 * High thr is custom and will apply 0.0
									 * Final consequence, all pixels above 0.0 in EdgeAndSymmetry filtered image will be applied and become 0.0
									 * This setting cannot be reproduced with CiliaQ Preparator of this version since this whole procedure is not reproducible. 
									 */
								}else {
									/** 
									 * High was set custom and value entered, low was other, value 0.0
									 * Old version consequence: low is custom, low value 0.0, high is SomeAlg, high value someValueButNotStored
									 * Application:
									 * Low thr is custom and will apply 0.0
									 * High thr is someAlg and will apply custom alg, which cannot be find - a random conversion to mask will appear as high
									 * Outcome unpredictable
									 * Final consequence, unpredictable
									 * This setting cannot be reproduced with CiliaQ Preparator of this version since this whole procedure is not reproducible. 
									 */									
								}
								IJ.log("CiliaQ_Preparator cannot finally import this setting since it is from an older CiliaQ Preparator version (" + version + ") and running"
										+ "\nCiliaQ_Preparator version " + version + " with this setting was affected by a bug that has been fixed in version V0.1.2."
										+ "\nYou may only reproduce this setting by running your file in CiliaQ Preparator version " + version + ", which can be downloaded at"
										+ "\nhttps://github.com/hansenjn/CiliaQ_Preparator/releases/tag/" + version.toLowerCase() + " and manually installed to your FIJI/ImageJ."
										+ "\nRead more about the bug at the release notes of CiliaQ Preparator version V0.1.2:"
										+ "\nhttps://github.com/hansenjn/CiliaQ_Preparator/releases/tag/v0.1.2");
								IJ.error("CiliaQ_Preparator cannot import this setting since it is from an older CiliaQ Preparator version (" + version + ") and running"
										+ "\nCiliaQ_Preparator version " + version + " with this setting was affected by a bug that has been fixed in version V0.1.2."
										+ "\nYou may only reproduce this setting by running your file in CiliaQ Preparator version " + version + ", which can be downloaded at"
										+ "\nhttps://github.com/hansenjn/CiliaQ_Preparator/releases/tag/" + version.toLowerCase() + " and manually installed to your FIJI/ImageJ."
										+ "\nRead more about the bug at the release notes of CiliaQ Preparator version V0.1.2:"
										+ "\nhttps://github.com/hansenjn/CiliaQ_Preparator/releases/tag/v0.1.2");
								return false;
							}
						}						
					}
				}
			}
			
			//New from V0.2.0 check for version.
			if(line.contains("ImageJ version:")){
				String fileIJ = line.substring(line.lastIndexOf("\t")+1).trim();
				String nowIJ  = IJ.getFullVersion();
				if(!fileIJ.equals(nowIJ)){
					IJ.log(PLUGIN_NAME + ": NOTE - the loaded settings file was produced with ImageJ "
						+ fileIJ + ", but you are running ImageJ " + nowIJ + "."
						+ " Auto-threshold results can differ slightly between ImageJ versions.");
				}
			}
		}					
		br.close();
		fr.close();
	}catch (Exception e) {
		IJ.error("Problem with loading preferences (possibly a malformed or truncated settings file).");
		e.printStackTrace();
		return false;
	}
	return true;
}

/**
 * New in V0.2.0:
 * If a user selected a CiliaQ Preparator output image ("..._CQP.tif") instead of the
 * matching metadata file ("..._CQP.txt"), redirect to the .txt if it exists next to it.
 * Otherwise returns the original file unchanged. Added in V0.2.0.
 */
private File redirectTifToTxt(File chosen){
	if(chosen == null) return chosen;
	String path = chosen.getPath();
	String lower = path.toLowerCase();
	if(lower.endsWith(".tif") || lower.endsWith(".tiff")){
		String txtPath = path.substring(0, path.lastIndexOf(".")) + ".txt";
		File txt = new File(txtPath);
		if(txt.exists() && txt.canRead()){
			IJ.log(PLUGIN_NAME + ": you selected an image (" + chosen.getName()
				+ "); loading the matching metadata file instead (" + txt.getName() + ").");
			return txt;
		}
	}
	return chosen;
}

/**
 * @return the raw text after "<key>=" in the macro options, or null if the key is absent.
 * Handles bracketed values ("key=[a b c]"), the "//[" / "//]" form the ImageJ recorder
 * sometimes emits, and a key that sits at the very end of the option string.
 * 
 * Added for version v0.2.0 (August 2026)
 * 
 * Modification in v0.2.1:
 *	Read the value of "key=..." out of a macro option string.
 *
 *	Reason for the guard at the end of this method: this plugin has two kinds of
 *	macro option. Ones with a value, "c1-ridge-width=6", are read HERE. Bare flags with no value,
 *	"c1-ridge-no-crossings", MUST be read with macroFlag instead.
 *
 *	Mixing them up is silent and was a real bug: "resolveCrossings = macroValue(...) == null" is
 *	unconditionally TRUE for a bare flag, because this method looks for "key=" and finds nothing. The
 *	recorder wrote ridge-no-crossings and the reader ignored it. So if the key turns out to be present as
 *	a bare token, this method now SAYS SO rather than returning null and letting the caller guess.
 */
private String macroValue(String options, String key){
	if(options == null) return null;
	
	// Locate the first occurrence of "key=".
	int at = options.indexOf(key + "=");
	
	// Guard against a key that is a suffix of a longer key, e.g. "c1-blur=" inside
	// "c11-blur=". A valid key must either start the string or be preceded by a space;
	// if the match sits mid-token, keep searching for the next occurrence.
	while(at > 0 && options.charAt(at - 1) != ' '){
		at = options.indexOf(key + "=", at + 1);
	}
	
	// Key not present (either never found, or only ever found mid-token).
	if(at < 0){
		/*
		 * Before giving up: is the key there as a BARE FLAG? Then the caller used the wrong helper, and
		 * returning null would hide it - see this method's comment. Warn rather than fail, because the
		 * value is still recoverable by using macroFlag, and a warning reaches both the log and the
		 * metadata file via macroWarnings.
		 */
		if(macroFlag(options, key)){
			String m = "'" + key + "' is a flag with no value, so it must be read with macroFlag,"
					+ " not macroValue - this is a bug in the plugin, please report it";
			IJ.log(PLUGIN_NAME + ": " + m);
			if(macroWarnings == null)	macroWarnings = new java.util.ArrayList<String>();
			if(!macroWarnings.contains(m))	macroWarnings.add(m);
		}
		return null;
	}
	
	// Everything after the '=' of this key. The value is the start of this substring.
	String rest = options.substring(at + key.length() + 1);
	
	// Case 1: bracketed value "key=[a b c]" - used whenever the value can contain spaces.
	// Return the text between the brackets. A missing closing ']' means a malformed
	// option string, so return null rather than risk an out-of-range substring.	
	if(rest.startsWith("[")){
		int close = rest.indexOf("]");
		return close < 0 ? null : rest.substring(1, close);
	}
	
	// Case 2: the recorder sometimes wraps values as "key=//[a b c//]" instead of
	// square brackets. Handle that form the same way, stripping the 3-char markers.
	if(rest.startsWith("//[")){
		int close = rest.indexOf("//]");
		return close < 0 ? null : rest.substring(3, close);
	}
	
	// Case 3: an unbracketed value ends at the next space. If there is no further space,
	// the key is the last token in the string, so the value runs to the end.
	int space = rest.indexOf(" ");
	return space < 0 ? rest : rest.substring(0, space);
}

/**
 * Read a numeric macro option and parse it as a double.
 *
 * Looks the key up via {@link #macroValue}. If the key is absent or its value is empty,
 * the fallback is returned unchanged. German-style decimals ("0,5") are normalised to a
 * dot before parsing, but only when there is no dot already, so a grouped value is not
 * corrupted. If the text is present but not a valid number, a message is logged and the
 * fallback is used rather than throwing.
 *
 * @param options  the full macro option string (may be null)
 * @param key      the option name, without the trailing '='
 * @param fallback the value to use if the key is missing, empty, or unparseable
 * @return the parsed value, or fallback
 */
private double macroDouble(String options, String key, double fallback){
	String text = macroValue(options, key);
	if(text == null || text.length() == 0) return fallback;
	if(text.contains(",") && !text.contains(".")) text = text.replace(",", ".");
	try{ return Double.parseDouble(text); }
	catch(NumberFormatException e){
		IJ.log(PLUGIN_NAME + ": could not read '" + key + "=" + text + "', using " + fallback);
		return fallback;
	}
}

/**
 * Read a numeric option and return it as an int.
 *
 * Delegates to {@link #macroDouble} and rounds the result to the nearest integer, so that
 * a value recorded with a decimal part (e.g. "3.0") still reads back correctly. Shares all
 * of macroDouble's tolerance: missing, empty or malformed values fall back silently.
 *
 * @param options  the full macro option string (may be null)
 * @param key      the option name, without the trailing '='
 * @param fallback the value to use if the key is missing, empty, or unparseable
 * @return the rounded integer value, or fallback
 */
private int macroInt(String options, String key, int fallback){
	return (int) Math.round(macroDouble(options, key, fallback));
}

/** True if the line has no leading whitespace, i.e. it is a column-0 section header rather than an
 *  indented detail line. Settings detail lines are always tab-indented; processing-section headers
 *  are not, so this distinguishes "end of the settings block" from text that merely contains a header
 *  phrase. */
private static boolean isUnindentedHeader(String line){
	return line.length() > 0 && line.charAt(0) != '	' && line.charAt(0) != ' ';
}

/**
 * Test for a bare, valueless flag such as "c1-duplicate" or "keep-awake".
 *
 * The option string is padded with a leading and trailing space and the flag is searched
 * for being surrounded by spaces (" key "). This delimiting is essential: it prevents one flag
 * name from matching inside another (e.g. "c1-blur" inside "c1-preblur") and prevents a
 * flag from matching the prefix of a "key=value" option.
 *
 * @param options the full macro option string (may be null)
 * @param key     the flag name to look for
 * @return true if the flag is present as a standalone token, false otherwise
 */
private boolean macroFlag(String options, String key){
	if(options == null) return false;
	String padded = " " + options + " ";
	return padded.contains(" " + key + " ");
}

/**
 * Read an option whose value must be one of a fixed set of allowed strings.
 *
 * Resolution happens in three passes:
 *   1. If the key is absent, the fallback is returned immediately.
 *   2. An exact match against the allowed list is preferred.
 *   3. Failing that, a unique substring match is accepted, so abbreviations still work
 *      (e.g. "MIP" for the full stack-method label). If the substring matches more than
 *      one allowed value it is treated as ambiguous and rejected.
 * If nothing matches, a message is logged and the fallback is returned, so an unknown or
 * ambiguous value degrades gracefully instead of failing the run.
 *
 * @param options  the full macro option string (may be null)
 * @param key      the option name, without the trailing '='
 * @param allowed  the permitted values (typically one of the plugin's choice arrays)
 * @param fallback the value to use if the key is missing or unresolvable
 * @return the matched allowed value, or fallback
 */
private String macroChoice(String options, String key, String [] allowed, String fallback){
	String text = macroValue(options, key);
	if(text == null) return fallback;
	for(int i = 0; i < allowed.length; i++){
		if(allowed[i].equals(text)) return allowed[i];
	}
	// Second pass: accept a unique substring, so abbreviations still work.
	String hit = null;
	for(int i = 0; i < allowed.length; i++){
		if(allowed[i].contains(text)){
			if(hit != null){ hit = null; break; }
			hit = allowed[i];
		}
	}
	if(hit != null) return hit;
	
	/*
	 * New in V0.2.1:
	 * Fixing that an unrecognised macro value was reported to IJ.log ONLY, which nobody reads during a batch
	 * run, so a typo in a macro silently changed the method and the run looked successful. Now: We collect it
	 * and surface it through the progress reporter and the metadata file once the run starts.
	 */
	String msg = "unknown value for '" + key + "': '" + text + "' - using '" + fallback + "' instead";
	IJ.log(PLUGIN_NAME + ": " + msg);
	if(macroWarnings == null)	macroWarnings = new java.util.ArrayList<String>();
	macroWarnings.add(msg);
	
	return fallback;
}

/** Allocate every per-channel array. Called before any route fills them in. */
private void initChannelArrays(int n){
	channelIDs = new int [n];
	includeDuplicateChannel = new boolean [n];	Arrays.fill(includeDuplicateChannel, false);
	
	// New param in V0.2.1
	medianFilter = new boolean [n];				Arrays.fill(medianFilter, false);
	medianRadius = new double [n];				Arrays.fill(medianRadius, 1.0);
	
	// New params in V0.2.0
	preBlur = new boolean [n];					Arrays.fill(preBlur, false);
	preBlurRadius = new double [n];				Arrays.fill(preBlurRadius, 2.0);
	
	subtractBackground = new boolean [n];		Arrays.fill(subtractBackground, false);
	subtractBGRadius = new double [n];			Arrays.fill(subtractBGRadius, 10.0);
	
	// Initialized from V0.2.0 on  - before remained uninitialized
	divideBackground = new boolean [n];			Arrays.fill(divideBackground, false);
	divideBGRadius = new double [n];			Arrays.fill(divideBGRadius, 3.0);
	additionalBlur = new boolean [n];			Arrays.fill(additionalBlur, false);
	additionalBlurRadius = new double [n];		Arrays.fill(additionalBlurRadius, 0.5);
	
	chosenAlgorithms = new String [n];			Arrays.fill(chosenAlgorithms, "RenyiEntropy");
	customThr = new double [n];
	chosenStackMethods = new String [n];		Arrays.fill(chosenStackMethods, STACK_MIP);
	separateTimesteps = new boolean [n];		Arrays.fill(separateTimesteps, false);
	
	cannySettings = new ProcessSettings [n];
	
	// New methods from V0.2.1 on
	ridgeSettings = new ciliaQ_Prep_jnh.ridge_detection.RidgeSettings [n];
	spotSettings = new ciliaQ_Prep_jnh.spot_detection.SpotSettings [n];
	pendingRidgeLabels = new ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.Result [n][];
	pendingRidgeChannel = new int [n];
	expansionSettings = new ciliaQ_Prep_jnh.expansion.ExpansionSettings [n];
	fractionSettings = new ciliaQ_Prep_jnh.fraction_threshold.FractionSettings [n];
	maskVoxelCount = new long [n];				// zero-intensity loss warning
	zeroIntensityVoxelCount = new long [n];
	phansalkarSettings = new ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings [n];
	pendingExpansionLabels = new int [n][][];
	pendingExpansionChannel = new int [n];
	pendingExpansionMax = new int [n];
	
	//Hysteresis settings
	customLowThr = new double [n];
	customHighThr = new double [n];
	chosenLowAlg = new String [n];				Arrays.fill(chosenLowAlg, "Triangle");
	chosenHighAlg = new String [n];				Arrays.fill(chosenHighAlg, "Otsu");
}

/**
 * Show dialogs to enter settings
 * */
private boolean enterSettings() {
	//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
	//-------------------------GenericDialog--------------------------------------
	//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
		
	GenericDialog gd = new GenericDialog(PLUGIN_NAME + " on " + System.getProperty("os.name") + " - set parameters");	
	//show Dialog-----------------------------------------------------------------
	//.setInsets(top, left, bottom)
	gd.setInsets(0,0,0);	gd.addMessage(PLUGIN_NAME + ", Version " + PLUGIN_VERSION + ", \u00a9 2019-2023 JN Hansen", SUPER_HEADING_FONT);
	gd.setInsets(5,0,0);	gd.addMessage("Channel to be segmented (i.e. reconstruction channel for CiliaQ)", HEADING_FONT);	
	gd.setInsets(0,10,0);	gd.addNumericField("Channel Nr (>= 1 & <= nr of channels)", channelIDs[0], 0);
	gd.setInsets(0,10,0);	gd.addCheckbox("Include also an unsegmented copy of the channel", includeDuplicateChannel [0]);
	gd.setInsets(0,10,0);	gd.addCheckbox("1. Remove speckles with median filter - radius (px)", medianFilter [0]);
	gd.setInsets(-23,100,0);	gd.addNumericField("", medianRadius[0], 2);
	gd.setInsets(0,10,0);	gd.addCheckbox("2. Smooth with Gaussian blur - radius", preBlur [0]);
	gd.setInsets(-23,100,0);	gd.addNumericField("", preBlurRadius[0], 2);
	gd.setInsets(0,10,0);	gd.addCheckbox("3. Subtract Background before segmentation - radius", subtractBackground [0]);
	gd.setInsets(-23,100,0);	gd.addNumericField("", subtractBGRadius[0], 2);
	gd.setInsets(0,10,0);	gd.addCheckbox("4. Divide by Background before segmentation - radius", divideBackground [0]);
	gd.setInsets(-23,100,0);	gd.addNumericField("", divideBGRadius[0], 2);
	gd.setInsets(0,10,0);	gd.addCheckbox("5. Smooth with Gaussian blur - radius", additionalBlur [0]);
	gd.setInsets(-23,100,0);	gd.addNumericField("", additionalBlurRadius[0], 2);
	gd.setInsets(0,10,0);	gd.addChoice("6. Segmentation method", algorithm, chosenAlgorithms[0]);
	
	gd.setInsets(0,0,0);	gd.addMessage("Most methods above open a second dialog for their own settings after"
			+ " pressing OK. Only the plain threshold algorithms and 'CUSTOM threshold' do not.", INSTRUCTIONS_FONT);	
	gd.setInsets(0,0,0);	gd.addNumericField("If 'CUSTOM threshold' was selected, specify threshold here", customThr[0], 2);
	gd.setInsets(0,10,0);	gd.addChoice("Stack handling (not used by CANNY 3D, PHANSALKAR, RIDGE or SPOT): ", stackMethod, chosenStackMethods[0]);	
	gd.setInsets(0,10,0);	gd.addCheckbox("Threshold every time step independently (not used by CANNY 3D, PHANSALKAR)", separateTimesteps [0]);		
	
	gd.setInsets(10,0,0);	gd.addNumericField("Segment more channels:", 0, 0);
	gd.setInsets(0,0,0);	gd.addMessage("(Indicate how many more channels you aim to segment here and CiliaQ Prepartor will provide "
			+ "more dialogs to select the corresponding segmentation settings)", INSTRUCTIONS_FONT);
	
	gd.setInsets(10,0,0);	gd.addMessage("GENERAL SETTINGS:", HEADING_FONT);	
	gd.setInsets(5,0,0);	gd.addChoice("Segmentation style: ", intensityVariant, intensityVariant [0]);
	
	gd.showDialog();
	//show Dialog-----------------------------------------------------------------

	//read and process variables--------------------------------------------------	

	{
		int channelIDTemp = (int) gd.getNextNumber();
		boolean includeDuplicateChannelTemp = gd.getNextBoolean();
		boolean medianFilterTemp = gd.getNextBoolean();
		double medianRadiusTemp = (double) gd.getNextNumber();
		boolean preBlurTemp = gd.getNextBoolean();
		double preBlurRadiusTemp = (double) gd.getNextNumber();
		boolean subtractBackgroundTemp = gd.getNextBoolean();
		double subtractBGRadiusTemp = (double) gd.getNextNumber();
		boolean divideBackgroundTemp = gd.getNextBoolean();
		double divideBGRadiusTemp = (double) gd.getNextNumber();
		boolean additionalBlurTemp = gd.getNextBoolean();
		double additionalBlurRadiusTemp = (double) gd.getNextNumber();
		String chosenAlgorithmsTemp = gd.getNextChoice();
		double customThrTemp = gd.getNextNumber();
		String chosenStackMethodsTemp = gd.getNextChoice();
		boolean separateTimestepsTemp = gd.getNextBoolean();

		nChannels = nChannels + (int) gd.getNextNumber();
		
		chosenImageStyle = gd.getNextChoice();
		if(chosenImageStyle.equals(intensityVariant [0])){
			keepIntensities = true;
		}
		
		// Introduced for init in Version V0.2.0
		initChannelArrays(nChannels);
		
		channelIDs [0] = channelIDTemp;
		includeDuplicateChannel [0] = includeDuplicateChannelTemp;
		medianFilter [0] = medianFilterTemp;
		medianRadius [0] = medianRadiusTemp;
		preBlur [0] = preBlurTemp;
		preBlurRadius [0] = preBlurRadiusTemp;
		subtractBackground [0] = subtractBackgroundTemp;
		subtractBGRadius [0] = subtractBGRadiusTemp;
		divideBackground [0] = divideBackgroundTemp;
		divideBGRadius [0] = divideBGRadiusTemp;
		additionalBlur [0] = additionalBlurTemp;
		additionalBlurRadius [0] = additionalBlurRadiusTemp;
		chosenAlgorithms [0] = chosenAlgorithmsTemp;
		customThr [0] = customThrTemp;
		chosenStackMethods [0] = chosenStackMethodsTemp;
		separateTimesteps [0] = separateTimestepsTemp;
	}
	System.gc();
	
	//read and process variables--------------------------------------------------
	if (gd.wasCanceled()) return false;
	
	for(int c = 1; c < nChannels; c++) {
		GenericDialog gd2 = new GenericDialog(PLUGIN_NAME + " on " + System.getProperty("os.name") + " - set parameters for channel " + (c+1));	
		//show Dialog-----------------------------------------------------------------
		//.setInsets(top, left, bottom)
		gd2.setInsets(0,0,0);	gd2.addMessage(PLUGIN_NAME + ", Version " + PLUGIN_VERSION + ", \u00a9 2019-2023 JN Hansen", SUPER_HEADING_FONT);
		gd2.setInsets(0,0,0);	gd2.addMessage("Select the preferences for channel to be segmented "+ (c+1) + " here.", INSTRUCTIONS_FONT);
		
		gd2.setInsets(0,10,0);	gd2.addNumericField("Channel Nr (>= 1 & <= nr of channels)", c+1, 0);
		gd2.setInsets(0,10,0);	gd2.addCheckbox("Include also an unsegmented copy of the channel", includeDuplicateChannel [0]);
		gd2.setInsets(0,10,0);	gd2.addCheckbox("1. Remove speckles with median filter - radius (px)", medianFilter [0]);
		gd2.setInsets(-23,100,0);	gd2.addNumericField("", medianRadius[0], 2);
		gd2.setInsets(0,10,0);	gd2.addCheckbox("2. Smooth with Gaussian blur - radius", preBlur [0]);
		gd2.setInsets(-23,100,0);	gd2.addNumericField("", preBlurRadius[0], 2);
		gd2.setInsets(0,10,0);	gd2.addCheckbox("3. Subtract Background before segmentation - radius", subtractBackground [0]);
		gd2.setInsets(-23,100,0);	gd2.addNumericField("", subtractBGRadius[0], 2);
		gd2.setInsets(0,10,0);	gd2.addCheckbox("4. Divide by Background before segmentation - radius", divideBackground [0]);
		gd2.setInsets(-23,100,0);	gd2.addNumericField("", divideBGRadius[0], 2);
		gd2.setInsets(0,10,0);	gd2.addCheckbox("5. Smooth with Gaussian blur - radius", additionalBlur [0]);
		gd2.setInsets(-23,100,0);	gd2.addNumericField("", additionalBlurRadius[0], 2);
		gd2.setInsets(0,10,0);	gd2.addChoice("6. Segmentation method", algorithm, chosenAlgorithms[0]);
		gd2.setInsets(0,0,0);	gd2.addMessage("Most methods above open a second dialog for their own settings after"
				+ " pressing OK. Only the plain threshold algorithms and 'CUSTOM threshold' do not.", INSTRUCTIONS_FONT);
		gd2.setInsets(0,0,0);	gd2.addNumericField("If 'CUSTOM threshold' was selected, specify threshold here", customThr[0], 2);
		gd2.setInsets(0,10,0);	gd2.addChoice("Stack handling (not used by CANNY 3D, PHANSALKAR, RIDGE or SPOT): ", stackMethod, chosenStackMethods[0]);	
		gd2.setInsets(0,10,0);	gd2.addCheckbox("Threshold every time step independently (not used by CANNY 3D, PHANSALKAR)", separateTimesteps [0]);		
		gd2.showDialog();

		//read and process variables--------------------------------------------------
		channelIDs [c] = (int) gd2.getNextNumber();
		includeDuplicateChannel [c] = gd2.getNextBoolean();
		medianFilter [c] = gd2.getNextBoolean();
		medianRadius [c] = (double) gd2.getNextNumber();
		preBlur [c] = gd2.getNextBoolean();
		preBlurRadius [c] = (double) gd2.getNextNumber();
		subtractBackground [c] = gd2.getNextBoolean();
		subtractBGRadius [c] = (double) gd2.getNextNumber();
		divideBackground [c] = gd2.getNextBoolean();
		divideBGRadius [c] = (double) gd2.getNextNumber();
		additionalBlur [c] = gd2.getNextBoolean();
		additionalBlurRadius [c] = (double) gd2.getNextNumber();
		chosenAlgorithms [c] = gd2.getNextChoice();
		customThr [c] = gd2.getNextNumber();
		chosenStackMethods [c] = gd2.getNextChoice();
		separateTimesteps [c] = gd2.getNextBoolean();
		
		//read and process variables--------------------------------------------------
		if (gd2.wasCanceled()) return false;
	}	
	System.gc();
	return true;
}

/** Route a warning to a dialog when interactive, to the log otherwise. */
private void warn(String message){
	IJ.log(PLUGIN_NAME + " WARNING: " + message);
	if(showGUIs && !GraphicsEnvironment.isHeadless()){
		IJ.showMessage(PLUGIN_NAME + " - warning", message);
	}
}

/**
 * Check the settings once, whatever route they arrived by. Returns false only for
 * problems that would make the run meaningless; everything else warns and continues.
 */
private boolean validateSettings(){
	if(channelIDs == null || channelIDs.length == 0){
		IJ.error(PLUGIN_NAME + ": no channel was configured.");
		return false;
	}

	for(int c = 0; c < channelIDs.length; c++){
		if(channelIDs [c] < 1){
			IJ.error(PLUGIN_NAME + ": channel number " + channelIDs [c] + " is not valid (must be >= 1).");
			return false;
		}
		// The same channel twice means the second pass segments an already-segmented
		// channel, which silently produces nonsense.
		for(int d = 0; d < c; d++){
			if(channelIDs [c] == channelIDs [d]){
				IJ.error(PLUGIN_NAME + ": channel " + channelIDs [c] + " is configured twice."
						+ " The second run would segment the output of the first."
						+ " Insert different channel IDs for different channels.");
				return false;
			}
		}

		// Radii and sigmas.
		if(preBlur [c] && preBlurRadius [c] <= 0){
			IJ.error(PLUGIN_NAME + ": the pre-segmentation blur radius must be > 0.");
			return false;
		}
		if(subtractBackground [c] && subtractBGRadius [c] <= 0){
			IJ.error(PLUGIN_NAME + ": the rolling-ball radius must be > 0.");
			return false;
		}
		if(divideBackground [c] && divideBGRadius [c] <= 0){
			IJ.error(PLUGIN_NAME + ": the divide-by-background radius must be > 0.");
			return false;
		}
		if(additionalBlur [c] && additionalBlurRadius [c] <= 0){
			IJ.error(PLUGIN_NAME + ": the Gaussian blur radius must be > 0.");
			return false;
		}

		// Divide-by-background computes value / blurredValue in 32-bit. Where the
		// blurred background is ~0 the result is Inf or NaN, which then never passes a
		// finite threshold - the cilia silently vanish.
		if(divideBackground [c]){
			warn("Channel " + channelIDs [c] + ": 'Divide by Background' divides by a blurred"
				+ "\ncopy of the image. If the background of this channel is (close to) zero -"
				+ "\nfor example in a channel that was already background-subtracted - the"
				+ "\nresult is infinite or undefined and nothing will be detected."
				+ "\nPrefer 'Subtract Background' for such images.");
		}

		// Two blurs in a row is legal but rarely intended.
		if(preBlur [c] && additionalBlur [c]){
			warn("Channel " + channelIDs [c] + ": both the pre-background blur (" + df3.format(preBlurRadius [c])
				+ ")\nand the post-background blur (" + df3.format(additionalBlurRadius [c])
				+ ")\nare enabled. The image is smoothed twice; the effective smoothing is"
				+ "\nlarger than either value. This is only useful if you deliberately want to"
				+ "\nsmooth before AND after background removal.");
		}

		// A blur wider than the structures removes them.
		if(preBlur [c] && subtractBackground [c] && preBlurRadius [c] >= subtractBGRadius [c]){
			warn("Channel " + channelIDs [c] + ": the pre-blur radius (" + df3.format(preBlurRadius [c])
				+ ") is not smaller than the rolling-ball radius (" + df3.format(subtractBGRadius [c])
				+ "). Smoothing at the scale of the background estimate largely cancels the"
				+ " background subtraction. A pre-blur of about a quarter of the rolling-ball"
				+ " radius is a reasonable starting point.");
		}

		// CUSTOM threshold with the default value is almost always an oversight.
		if(chosenAlgorithms [c].equals("CUSTOM threshold") && customThr [c] == 0.0){
			warn("Channel " + channelIDs [c] + ": segmentation method is 'CUSTOM threshold'"
				+ "\nbut the threshold is 0, so every voxel above zero will be kept."
				+ "\nDid you mean to enter a threshold value?");
		}

		// Hysteresis with the low threshold stricter than the high one cannot grow.
		if(chosenAlgorithms [c].equals("HYSTERESIS threshold")
				&& chosenLowAlg [c].equals(chosenHighAlg [c])
				&& !chosenLowAlg [c].equals(hystAlgorithms[0])){
			warn("Channel " + channelIDs [c] + ": hysteresis thresholding was selected but the"
				+ "\nlow and high thresholds use the same method (" + chosenLowAlg [c] + "),"
				+ "\nso it behaves like a plain threshold. Pick a more inclusive method for the"
				+ "\nlow threshold, for example Triangle low and Otsu high.");
		}

		// The 3D suite is needed for two methods. Check now, not after an hour of batch.
		if(chosenAlgorithms [c].equals("CANNY 3D") || chosenAlgorithms [c].equals("HYSTERESIS threshold")){
			try{
				Class.forName("mcib3d.image3d.ImageInt");
			}catch(ClassNotFoundException e){
				IJ.error(PLUGIN_NAME + ": '" + chosenAlgorithms [c] + "' requires the 3D ImageJ Suite,"
					+ "\nwhich is not installed. See"
					+ "\nhttps://github.com/hansenjn/CiliaQ/wiki/Installing-CiliaQ");
				return false;
			}
		}
	}

	if(chosenImageStyle.equals(intensityVariant[1])){
		warn("Segmentation style is set to produce a BINARY image. CiliaQ can still reconstruct"
			+ "\ncilia from it and this option can be helpful if you have 0 background pixel values."
			+ "\nHowever, note that intensity values derived from the mask / reconstruction channel"
			+ "\nwill be meaningless. Intensity measures on all other channels will be fine."
			+ "\nUse 'Keep intensities above threshold' unless you specifically"
			+ " need a binary mask.");
	}
	return true;
}

/** Checks that need the image. Returns false to skip this image, not to abort the batch. */
private boolean validateAgainstImage(ImagePlus imp, int task){
	boolean ok = true;
	for(int c = 0; c < channelIDs.length; c++){
		if(channelIDs [c] > imp.getNChannels()){
			progress.notifyMessage("Task " + (task+1) + ": settings ask for channel "
				+ channelIDs [c] + " but the image has only " + imp.getNChannels()
				+ " channel(s). Skipping this image.", ProgressReporter.ERROR);
			ok = false;
		}
	}
	// Stack handling mismatches are a very common and completely silent user error.
	for(int c = 0; c < channelIDs.length; c++){
		if(chosenAlgorithms [c].equals("CANNY 3D")) continue;
		if(imp.getNSlices() > 1 && chosenStackMethods [c].equals(STACK_NO_STACK)){
			progress.notifyMessage("Task " + (task+1) + ": stack handling is set to '"
				+ STACK_NO_STACK + "' but the image has " + imp.getNSlices()
				+ " slices. The threshold will be taken from the first slice only.",
				ProgressReporter.NOTIFICATION);
		}else if(imp.getNSlices() > 1 && chosenStackMethods [c].equals(STACK_PER_SLICE)
				&& (chosenAlgorithms [c].equals("HYSTERESIS threshold")
					|| chosenAlgorithms [c].equals(EXPANSION_SEEDS))){
			/*	A per-slice threshold cannot apply to these two. Both grow objects THROUGH z -
			 *	hysteresis via the 3D Hysteresis Thresholding plugin, expansion via 26-connected
			 *	growth - so they need one threshold pair for the whole stack. A different threshold
			 *	per slice would cut objects at slice boundaries. Say so rather than pool silently. */
			progress.notifyMessage("Task " + (task+1) + ": stack handling '" + STACK_PER_SLICE
				+ "' cannot be used with '" + chosenAlgorithms [c] + "', which needs one threshold"
				+ " for the whole stack because it connects objects across slices."
				+ " The pooled-histogram threshold is used for this channel instead.",
				ProgressReporter.NOTIFICATION);
		}else if(imp.getNSlices() == 1 && !chosenStackMethods [c].equals(STACK_NO_STACK)){
			progress.notifyMessage("Task " + (task+1) + ": the image is a single slice, so"
				+ " stack handling '" + chosenStackMethods [c] + "' has no effect.",
				ProgressReporter.NOTIFICATION);
		}
		if(separateTimesteps [c] && imp.getNFrames() == 1){
			progress.notifyMessage("Task " + (task+1) + ": 'threshold every time step"
				+ " independently' is on but the image has a single frame.",
				ProgressReporter.NOTIFICATION);
		}
	}
	if(imp.getBitDepth() == 8){
		for(int c = 0; c < channelIDs.length; c++){
			if(divideBackground [c]){
				progress.notifyMessage("Task " + (task+1) + ": the image is 8-bit and"
					+ " divide-by-background produces 32-bit ratios; the dynamic range"
					+ " available for thresholding is very small.", ProgressReporter.NOTIFICATION);
			}
		}
	}
	return ok;
}

/**
 * Set all settings from a macro option string. Missing keys keep their defaults.
 * Mirrors CiliaQMain.readSettingsFromMacroString.
 * @return false if the settings could not be established.
 */
private boolean readSettingsFromMacroString(String options){
	IJ.log(PLUGIN_NAME + " " + PLUGIN_VERSION + ": reading settings from macro options.");
	IJ.log("  options: " + options);

	// In macro mode we always work on the front-most image; the multi-task manager and
	// the file dialogs are GUI-only.
	selectedTaskVariant = taskVariant[0];
	showGUIs = false;
	keepAwake = macroFlag(options, "keep-awake");

	chosenOutputName = macroChoice(options, "output", outputVariant, outputVariant[0]);
	ChosenNumberFormat = macroChoice(options, "number-format", nrFormats, nrFormats[0]);
	if(ChosenNumberFormat.equals(nrFormats[0])){
		df6.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
		df3.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
		df0.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
	}else{
		df6.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.GERMANY));
		df3.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.GERMANY));
		df0.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.GERMANY));
	}

	// Route A: a metadata file supplies every per-channel setting (Stage 3).
	if(macroValue(options, "settings") != null){
		selectedSettingsVariant = settingsMethod[1];
		return importSettings();
	}

	// Route B: per-channel settings come from the option string itself.
	selectedSettingsVariant = settingsMethod[0];
	chosenImageStyle = macroChoice(options, "segmentation-style", intensityVariant, intensityVariant[0]);
	keepIntensities = chosenImageStyle.equals(intensityVariant[0]);

	nChannels = macroInt(options, "channels", 1);
	if(nChannels < 1){
		IJ.error(PLUGIN_NAME + ": channels=" + nChannels + " is not valid.");
		return false;
	}
	initChannelArrays(nChannels);

	for(int c = 0; c < nChannels; c++){
		String p = "c" + (c+1) + "-";
		channelIDs [c] = macroInt(options, p + "nr", c+1);
		includeDuplicateChannel [c] = macroFlag(options, p + "duplicate");

		medianFilter [c] = macroValue(options, p + "median") != null;
		if(medianFilter [c]) medianRadius [c] = macroDouble(options, p + "median", 1.0);
		
		preBlur [c] = macroValue(options, p + "preblur") != null;
		if(preBlur [c]) preBlurRadius [c] = macroDouble(options, p + "preblur", 2.0);

		subtractBackground [c] = macroValue(options, p + "subtract-bg") != null;
		if(subtractBackground [c]) subtractBGRadius [c] = macroDouble(options, p + "subtract-bg", 10.0);

		divideBackground [c] = macroValue(options, p + "divide-bg") != null;
		if(divideBackground [c]) divideBGRadius [c] = macroDouble(options, p + "divide-bg", 3.0);

		additionalBlur [c] = macroValue(options, p + "blur") != null;
		if(additionalBlur [c]) additionalBlurRadius [c] = macroDouble(options, p + "blur", 0.5);

		chosenAlgorithms [c] = macroChoice(options, p + "method", algorithm, "RenyiEntropy");
		customThr [c] = macroDouble(options, p + "custom-thr", 0.0);
		chosenStackMethods [c] = macroChoice(options, p + "stack", stackMethod, STACK_MIP);
		separateTimesteps [c] = macroFlag(options, p + "separate-timesteps");

		if(chosenAlgorithms [c].equals("HYSTERESIS threshold")){
			chosenLowAlg [c] = macroChoice(options, p + "hyst-low", hystAlgorithms, "Triangle");
			chosenHighAlg [c] = macroChoice(options, p + "hyst-high", hystAlgorithms, "Otsu");
			customLowThr [c] = macroDouble(options, p + "hyst-low-thr", 0.0);
			customHighThr [c] = macroDouble(options, p + "hyst-high-thr", 0.0);
		}else if(chosenAlgorithms [c].equals(RIDGE_DETECTION)){
			/*
			 * The simple keys are always read. The ADVANCED keys are honoured only when actually
			 * present: if any of them is given, advancedOverride is set so deriveFrom() will not
			 * recompute over the top of it. Without that, a macro could not pin sigma/thresholds at all.
			 */
			ridgeSettings [c] = ciliaQ_Prep_jnh.ridge_detection.RidgeSettings.initDefault();
			ridgeSettings [c].lineWidth = macroDouble(options, p + "ridge-width", 2.0);
			ridgeSettings [c].sensitivity = macroDouble(options, p + "ridge-sensitivity", 0.6);
			ridgeSettings [c].minLength = macroDouble(options, p + "ridge-min-length", 10.0);
			ridgeSettings [c].maxLength = macroDouble(options, p + "ridge-max-length", 0.0);
			/*	macroFlag, NOT macroValue. macroValue looks for "key=" and so returns null for a BARE
			 *	flag, which made "macroValue(...) == null" unconditionally true - ridge-no-crossings was
			 *	written by the recorder and then silently ignored on the way back in. */
			ridgeSettings [c].resolveCrossings = !macroFlag(options, p + "ridge-no-crossings");
			ridgeSettings [c].darkLine = macroFlag(options, p + "ridge-dark");
			/*	Negative flags, because these three default to TRUE - same convention as
			 *	ridge-no-crossings. Without them a GUI user could switch a detector option off and
			 *	neither the recorded macro nor the optimizer could reproduce it. */
			ridgeSettings [c].correctPosition = !macroFlag(options, p + "ridge-no-correct-position");
			ridgeSettings [c].estimateWidth = !macroFlag(options, p + "ridge-no-estimate-width");
			ridgeSettings [c].extendLine = !macroFlag(options, p + "ridge-no-extend-line");
			if(macroValue(options, p + "ridge-sigma") != null
					|| macroValue(options, p + "ridge-upper") != null
					|| macroValue(options, p + "ridge-lower") != null){
				ridgeSettings [c].sigma = macroDouble(options, p + "ridge-sigma",
						ciliaQ_Prep_jnh.ridge_detection.RidgeSettings.sigmaForWidth(ridgeSettings [c].lineWidth));
				ridgeSettings [c].upperThreshold = macroDouble(options, p + "ridge-upper", 0.0);
				ridgeSettings [c].lowerThreshold = macroDouble(options, p + "ridge-lower", 0.0);
				ridgeSettings [c].advancedOverride = true;
			}
		}else if(ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.NAME.equals(chosenAlgorithms [c])){
			phansalkarSettings [c] = ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.initDefault();
			phansalkarSettings [c].radius = macroDouble(options, p + "phansalkar-radius", 15.0);
			phansalkarSettings [c].k = macroDouble(options, p + "phansalkar-k",
					ciliaQ_Prep_jnh.local_threshold.Phansalkar.K);
			phansalkarSettings [c].r = macroDouble(options, p + "phansalkar-r",
					ciliaQ_Prep_jnh.local_threshold.Phansalkar.R);
			phansalkarSettings [c].p = macroDouble(options, p + "phansalkar-p",
					ciliaQ_Prep_jnh.local_threshold.Phansalkar.P);
			phansalkarSettings [c].q = macroDouble(options, p + "phansalkar-q",
					ciliaQ_Prep_jnh.local_threshold.Phansalkar.Q);
		}else if(ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.isFractionMethod(
				chosenAlgorithms [c])){
			/* Both variants read the same keys; the unused ones simply keep their defaults. */
			fractionSettings [c] = ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.initDefault();
			fractionSettings [c].fGlobal = macroDouble(options, p + "fraction-f", 0.05);
			fractionSettings [c].fLocal = macroDouble(options, p + "fraction-f-local", 0.20);
			fractionSettings [c].tileSize = (int) Math.round(
					macroDouble(options, p + "fraction-tile", 64.0));
			fractionSettings [c].bgPercentile = macroDouble(options, p + "fraction-bg-percentile", 50.0);
			fractionSettings [c].signalPercentileGlobal = macroDouble(options,
					p + "fraction-signal-percentile", 99.9);
			fractionSettings [c].signalPercentileLocal = macroDouble(options,
					p + "fraction-signal-percentile-local", 99.0);
		}else if(chosenAlgorithms [c].equals(EXPANSION_SEEDS)){
			/* All three seed sources' parameters are accepted regardless of the chosen source */
			expansionSettings [c] = ciliaQ_Prep_jnh.expansion.ExpansionSettings.initDefault();
			expansionSettings [c].seedSource = macroChoice(options, p + "exp-seeds",
					ciliaQ_Prep_jnh.expansion.ExpansionSettings.seedSources,
					ciliaQ_Prep_jnh.expansion.ExpansionSettings.SEED_RIDGE);
			expansionSettings [c].lowThrAlgorithm = macroChoice(options, p + "exp-low",
					ciliaQ_Prep_jnh.expansion.ExpansionSettings.lowThrAlgorithms, "Triangle");
			expansionSettings [c].customLowThr = macroDouble(options, p + "exp-low-thr", 0.0);
			expansionSettings [c].seedThrAlgorithm = macroChoice(options, p + "exp-seed-alg",
					ciliaQ_Prep_jnh.expansion.ExpansionSettings.lowThrAlgorithms, "Otsu");
			expansionSettings [c].customSeedThr = macroDouble(options, p + "exp-seed-thr", 0.0);
			expansionSettings [c].minPieceSize = (int) Math.round(
					macroDouble(options, p + "exp-min-piece", 200.0));
			expansionSettings [c].ridgeWidth = macroDouble(options, p + "exp-ridge-width", 2.0);
			expansionSettings [c].ridgeSensitivity = macroDouble(options, p + "exp-ridge-sensitivity", 0.6);
			expansionSettings [c].spotDiameter = macroDouble(options, p + "exp-spot-diameter", 2.0);
			expansionSettings [c].spotSensitivity = macroDouble(options, p + "exp-spot-sensitivity", 0.5);
		}else if(chosenAlgorithms [c].equals(SPOT_DETECTION)){
			/* min-intensity accepts the literal word 'auto', which is what createRecordString writes. */
			spotSettings [c] = ciliaQ_Prep_jnh.spot_detection.SpotSettings.initDefault();
			spotSettings [c].diameter = macroDouble(options, p + "spot-diameter", 2.0);
			spotSettings [c].variation = macroDouble(options, p + "spot-variation", 1.0);
			/*	The default is diameter-corrected, because the DoG response is scale-normalised by
			 *	sigma^2 - see SpotSettings.defaultSensitivity(int,double). The bit depth is not known
			 *	here (no image is open), so 8-bit is assumed for the default only; an explicit
			 *	spot-sensitivity in the macro overrides it, and sensitivityWarning re-checks against the
			 *	real bit depth during processing. */
			spotSettings [c].sensitivity = macroDouble(options, p + "spot-sensitivity",
					ciliaQ_Prep_jnh.spot_detection.SpotSettings.defaultSensitivity(
							8, spotSettings [c].diameter));
			spotSettings [c].scaleRatio = macroDouble(options, p + "spot-ratio", 1.6);
			String minTxt = macroValue(options, p + "spot-min-intensity");
			if(minTxt == null || minTxt.length() == 0 || minTxt.equalsIgnoreCase("auto")){
				spotSettings [c].autoMinIntensity = true;
			}else{
				spotSettings [c].autoMinIntensity = false;
				spotSettings [c].minIntensity = macroDouble(options, p + "spot-min-intensity", 0.0);
			}
		}else if(chosenAlgorithms [c].equals("CANNY 3D")){
			cannySettings [c] = ProcessSettings.initDefault();
			cannySettings [c].setGaussSigma(macroDouble(options, p + "canny-sigma", 1.0));
			cannySettings [c].setCannyAlpha(macroDouble(options, p + "canny-alpha", 5.0));
			cannySettings [c].setLowThresholdAlgorithm(macroChoice(options, p + "canny-low",
					ProcessSettings.thrAlgorithms, "Triangle"));
			cannySettings [c].setHighThresholdAlgorithm(macroChoice(options, p + "canny-high",
					ProcessSettings.thrAlgorithms, "Otsu"));
			cannySettings [c].setLowThr(macroDouble(options, p + "canny-low-thr", 0.0));
			cannySettings [c].setHighThr(macroDouble(options, p + "canny-high-thr", 0.0));
		}
	}
	return true;
}

/**
 * @return an option string that reproduces the current settings when passed back to this
 * plugin. Mirrors CiliaQMain.createRecordString.
 */
private String createRecordString(){
	String s = "";
	s += "process=[" + taskVariant[0] + "] ";
	s += "output=[" + chosenOutputName + "] ";
	s += "number-format=[" + ChosenNumberFormat + "] ";
	s += "segmentation-style=[" + chosenImageStyle + "] ";
	s += "channels=" + channelIDs.length + " ";
	for(int c = 0; c < channelIDs.length; c++){
		String p = "c" + (c+1) + "-";
		s += p + "nr=" + df0.format(channelIDs [c]) + " ";
		if(includeDuplicateChannel [c])	s += p + "duplicate ";
		if(medianFilter [c])			s += p + "median=" + df3.format(medianRadius [c]) + " ";
		if(preBlur [c])					s += p + "preblur=" + df3.format(preBlurRadius [c]) + " ";
		if(subtractBackground [c])		s += p + "subtract-bg=" + df3.format(subtractBGRadius [c]) + " ";
		if(divideBackground [c])		s += p + "divide-bg=" + df3.format(divideBGRadius [c]) + " ";
		if(additionalBlur [c])			s += p + "blur=" + df3.format(additionalBlurRadius [c]) + " ";
		s += p + "method=[" + chosenAlgorithms [c] + "] ";
		if(chosenAlgorithms [c].equals("CUSTOM threshold")){
			s += p + "custom-thr=" + df6.format(customThr [c]) + " ";
		}else if(chosenAlgorithms [c].equals("HYSTERESIS threshold")){
			s += p + "hyst-low=[" + chosenLowAlg [c] + "] ";
			s += p + "hyst-high=[" + chosenHighAlg [c] + "] ";
			if(chosenLowAlg [c].equals(hystAlgorithms[0]))	s += p + "hyst-low-thr=" + df6.format(customLowThr [c]) + " ";
			if(chosenHighAlg [c].equals(hystAlgorithms[0]))	s += p + "hyst-high-thr=" + df6.format(customHighThr [c]) + " ";
		}else if(chosenAlgorithms [c].equals(RIDGE_DETECTION)){
			s += p + "ridge-width=" + df3.format(ridgeSettings [c].lineWidth) + " ";
			s += p + "ridge-sensitivity=" + df3.format(ridgeSettings [c].sensitivity) + " ";
			s += p + "ridge-min-length=" + df3.format(ridgeSettings [c].minLength) + " ";
			if(ridgeSettings [c].maxLength > 0)		s += p + "ridge-max-length=" + df3.format(ridgeSettings [c].maxLength) + " ";
			if(!ridgeSettings [c].resolveCrossings)	s += p + "ridge-no-crossings ";
			if(ridgeSettings [c].darkLine)			s += p + "ridge-dark ";
			if(!ridgeSettings [c].correctPosition)	s += p + "ridge-no-correct-position ";
			if(!ridgeSettings [c].estimateWidth)	s += p + "ridge-no-estimate-width ";
			if(!ridgeSettings [c].extendLine)		s += p + "ridge-no-extend-line ";
			/* Record the advanced values only when the user overrode them, so a recorded macro
			 * reproduces the run exactly without pinning values that should stay derived. */
			if(ridgeSettings [c].advancedOverride){
				s += p + "ridge-sigma=" + df6.format(ridgeSettings [c].sigma) + " ";
				s += p + "ridge-upper=" + df6.format(ridgeSettings [c].upperThreshold) + " ";
				s += p + "ridge-lower=" + df6.format(ridgeSettings [c].lowerThreshold) + " ";
			}
		}else if(ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.NAME.equals(chosenAlgorithms [c])){
			s += p + "phansalkar-radius=" + df3.format(phansalkarSettings [c].radius) + " ";
			s += p + "phansalkar-k=" + df6.format(phansalkarSettings [c].k) + " ";
			s += p + "phansalkar-r=" + df6.format(phansalkarSettings [c].r) + " ";
			s += p + "phansalkar-p=" + df6.format(phansalkarSettings [c].p) + " ";
			s += p + "phansalkar-q=" + df6.format(phansalkarSettings [c].q) + " ";
		}else if(ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.isFractionMethod(
				chosenAlgorithms [c])){
			boolean loc = ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.LOCAL.equals(
					chosenAlgorithms [c]);
			if(loc){
				s += p + "fraction-f-local=" + df6.format(fractionSettings [c].fLocal) + " ";
				s += p + "fraction-tile=" + df0.format(fractionSettings [c].tileSize) + " ";
				s += p + "fraction-f=" + df6.format(fractionSettings [c].fGlobal) + " ";
				s += p + "fraction-signal-percentile-local="
						+ df3.format(fractionSettings [c].signalPercentileLocal) + " ";
			}else{
				s += p + "fraction-f=" + df6.format(fractionSettings [c].fGlobal) + " ";
				s += p + "fraction-signal-percentile="
						+ df3.format(fractionSettings [c].signalPercentileGlobal) + " ";
			}
			s += p + "fraction-bg-percentile=" + df3.format(fractionSettings [c].bgPercentile) + " ";
		}else if(chosenAlgorithms [c].equals(EXPANSION_SEEDS)){
			s += p + "exp-seeds=[" + expansionSettings [c].seedSource + "] ";
			s += p + "exp-low=[" + expansionSettings [c].lowThrAlgorithm + "] ";
			if(expansionSettings [c].customValueForLowThreshold())
				s += p + "exp-low-thr=" + df6.format(expansionSettings [c].customLowThr) + " ";
			s += p + "exp-min-piece=" + df0.format(expansionSettings [c].minPieceSize) + " ";
			/* Only the chosen source's parameters are written, so a recorded macro says what actually
			 * mattered rather than three sets of numbers of which two were ignored. */
			if(expansionSettings [c].seedSource.equals(
					ciliaQ_Prep_jnh.expansion.ExpansionSettings.SEED_RIDGE)){
				s += p + "exp-ridge-width=" + df3.format(expansionSettings [c].ridgeWidth) + " ";
				s += p + "exp-ridge-sensitivity=" + df3.format(expansionSettings [c].ridgeSensitivity) + " ";
			}else if(expansionSettings [c].seedSource.equals(
					ciliaQ_Prep_jnh.expansion.ExpansionSettings.SEED_HIGH)){
				s += p + "exp-seed-alg=[" + expansionSettings [c].seedThrAlgorithm + "] ";
				if(expansionSettings [c].customValueForSeedThreshold())
					s += p + "exp-seed-thr=" + df6.format(expansionSettings [c].customSeedThr) + " ";
			}else{
				s += p + "exp-spot-diameter=" + df3.format(expansionSettings [c].spotDiameter) + " ";
				s += p + "exp-spot-sensitivity=" + df6.format(expansionSettings [c].spotSensitivity) + " ";
			}
		}else if(chosenAlgorithms [c].equals(SPOT_DETECTION)){
			s += p + "spot-diameter=" + df3.format(spotSettings [c].diameter) + " ";
			s += p + "spot-variation=" + df3.format(spotSettings [c].variation) + " ";
			s += p + "spot-sensitivity=" + df6.format(spotSettings [c].sensitivity) + " ";
			s += p + "spot-ratio=" + df3.format(spotSettings [c].scaleRatio) + " ";
			s += p + "spot-min-intensity=" + (spotSettings [c].autoMinIntensity
					? "auto" : df6.format(spotSettings [c].minIntensity)) + " ";
		}else if(chosenAlgorithms [c].equals("CANNY 3D")){
			s += p + "canny-sigma=" + cannySettings [c].getGaussSigma() + " ";
			s += p + "canny-alpha=" + cannySettings [c].getCannyAlpha() + " ";
			s += p + "canny-low=[" + cannySettings [c].getLowThresholdAlgorithm() + "] ";
			s += p + "canny-high=[" + cannySettings [c].getHighThresholdAlgorithm() + "] ";
		}
		if(!chosenAlgorithms [c].equals("CANNY 3D")){
			s += p + "stack=[" + chosenStackMethods [c] + "] ";
			if(separateTimesteps [c])	s += p + "separate-timesteps ";
		}
	}
	return s;	// NOTE: trailing space kept on purpose, see below
}

ImagePlus divideByBackground(ImagePlus imp, double radius) {
	ImagePlus outImp = IJ.createHyperStack("divided image", imp.getWidth(), imp.getHeight(), 1, imp.getNSlices(), imp.getNFrames(), 32);
	outImp.setCalibration(imp.getCalibration());
	outImp.setOverlay(imp.getOverlay());
	ImagePlus tempImp;
	for(int s = 0; s < imp.getNSlices(); s++) {
		for(int t = 0; t < imp.getNFrames(); t++) {
			if(keepAwake) {
				stayAwake();
			}
			
			tempImp = IJ.createHyperStack("temp", imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
			for(int x = 0; x < imp.getWidth(); x++) {
				for(int y = 0; y < imp.getHeight(); y++) {
					tempImp.getStack().setVoxel(x, y, 0, imp.getStack().getVoxel(x, y, imp.getStackIndex(1 , s+1, t+1)-1));
				}
			}
			
			if(keepAwake) {
				stayAwake();
			}
			
			tempImp.getProcessor().blurGaussian(radius);
			for(int x = 0; x < imp.getWidth(); x++) {
				for(int y = 0; y < imp.getHeight(); y++) {
					outImp.getStack().setVoxel(x, y, outImp.getStackIndex(1, s+1, t+1)-1, 
							imp.getStack().getVoxel(x, y, imp.getStackIndex(1 , s+1, t+1)-1) 
							/ tempImp.getStack().getVoxel(x, y, 0));
				}
			}
		}
	}
	return outImp;	
}

ImagePlus blurGaussian(ImagePlus imp, double radius) {
	ImagePlus outImp = IJ.createHyperStack("divided image", imp.getWidth(), imp.getHeight(), 
			imp.getNChannels(), imp.getNSlices(), imp.getNFrames(), imp.getBitDepth());
	outImp.setCalibration(imp.getCalibration());
	outImp.setOverlay(imp.getOverlay());
	ImagePlus tempImp;
	for(int c = 0; c < imp.getNChannels(); c++) {
		for(int s = 0; s < imp.getNSlices(); s++) {
			for(int t = 0; t < imp.getNFrames(); t++) {
				if(keepAwake) {
					stayAwake();
				}
				
				tempImp = IJ.createHyperStack("temp", imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
				for(int x = 0; x < imp.getWidth(); x++) {
					for(int y = 0; y < imp.getHeight(); y++) {
						tempImp.getStack().setVoxel(x, y, 0, imp.getStack().getVoxel(x, y, imp.getStackIndex(c+1 , s+1, t+1)-1));
					}
				}
				tempImp.getProcessor().blurGaussian(radius);
				for(int x = 0; x < imp.getWidth(); x++) {
					for(int y = 0; y < imp.getHeight(); y++) {
						outImp.getStack().setVoxel(x, y, outImp.getStackIndex(c+1, s+1, t+1)-1, 
								tempImp.getStack().getVoxel(x, y, 0));
					}
				}
			}
		}
	}
	
	return outImp;	
}

private void addFooter(TextCollector tp, Date currentDate){
	tp.append("");
	tp.append("Datafile was generated on " + FullDateFormatter2.format(currentDate) + " by '"
			+PLUGIN_NAME+"', an ImageJ plug-in by Jan Niklas Hansen (jan.hansen@uni-bonn.de, https://github.com/hansenjn/CiliaQ_Preparator).");
	tp.append("The plug-in '"+PLUGIN_NAME+"' is distributed in the hope that it will be useful,"
			+ " but WITHOUT ANY WARRANTY; without even the implied warranty of"
			+ " MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.");
	tp.append("Plug-in version:	V"+PLUGIN_VERSION);
	tp.append("ImageJ version:	" + IJ.getFullVersion());   // NEW in V0.2.0
		
	// From V0.2.1 - provenance for 3D ImageJ Suite reproducibility (used by Canny 3D and Hysteresis).
	// Recorded only when a method that relies on it was selected; thresholding behaviour can change between versions.
	boolean any3DSuite = false;
	for(int c = 0; c < channelIDs.length; c++)
	    if("CANNY 3D".equals(chosenAlgorithms[c])
	       || "HYSTERESIS threshold".equals(chosenAlgorithms[c])) { any3DSuite = true; break; }
	if(any3DSuite)
	    tp.append("3D ImageJ Suite version:	" + mcib3dVersion());
}

/**
 * @param channel: 1 <= channel <= # channels
 * */
private ImagePlus copyChannel(ImagePlus imp, int channel, boolean adjustDisplayRangeTo12bit, boolean copyOverlay){
	ImagePlus impNew = IJ.createHyperStack("channel image", imp.getWidth(), imp.getHeight(), 1, imp.getNSlices(), imp.getNFrames(), imp.getBitDepth());
	int index = 0, indexNew = 0;
	
	for(int x = 0; x < imp.getWidth(); x++){
		for(int y = 0; y < imp.getHeight(); y++){
			for(int s = 0; s < imp.getNSlices(); s++){
				for(int f = 0; f < imp.getNFrames(); f++){
					index = imp.getStackIndex(channel, s+1, f+1)-1;
					indexNew = impNew.getStackIndex(1, s+1, f+1)-1;
					impNew.getStack().setVoxel(x, y, indexNew, imp.getStack().getVoxel(x, y, index));
				}					
			}
		}
	}
	if(adjustDisplayRangeTo12bit)	impNew.setDisplayRange(0, 4095);
	if(copyOverlay && imp.getOverlay() != null)	impNew.setOverlay(imp.getOverlay().duplicate());
	impNew.setCalibration(imp.getCalibration());
	return impNew;
}

/**
 * The fraction-of-range threshold per time step.
 *
 * Mirrors getThresholds, and deliberately reuses the SAME stack-processing branches, so the two new
 * methods honour the user's "apply threshold determined in ..." choice exactly as every existing
 * method does. In particular, for the maximum-projection option both the global statistics AND the
 * local tile grid are computed on the projection and the resulting surface is applied to every slice.
 *
 * Statistics come from the image passed in, which the caller has already pre-blurred, background
 * subtracted/divided and post-blurred, per decision.
 */
private ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result [][] getFractionThresholds(
		ImagePlus imp, ciliaQ_Prep_jnh.fraction_threshold.FractionSettings st, boolean localMethod,
		String chosenMethod, boolean separateTimeSteps, ProgressReporter progress){
	int startGroup = 1, endGroup = imp.getNFrames();
	int nGroups = separateTimeSteps ? (endGroup-startGroup+1) : 1;
	ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result [][] out =
			new ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result [nGroups][];
	for(int i = 0; i < nGroups; i++){
		int from = separateTimeSteps ? startGroup + i : startGroup;
		int to = separateTimeSteps ? startGroup + i : endGroup;
		ImagePlus selectedImp = getSelectedTimepoints(imp, from, to, false);
		if(chosenMethod.equals(STACK_PER_SLICE)){
			/*	One statistic set, and for the local method one whole surface, PER SLICE. This is
			 *	the case the option exists for - a stack whose brightness changes with depth. */
			int nSl = imp.getNSlices();
			out [i] = new ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result [nSl];
			for(int sl = 0; sl < nSl; sl++){
				if(keepAwake)	stayAwake();
				ImagePlus oneSlice = getSingleSlice(selectedImp, sl + 1);
				out [i][sl] = ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.compute(
						oneSlice, st, localMethod);
				oneSlice.changes = false;
				oneSlice.close();
			}
		}else{
			ImagePlus statsImp = selectedImp;
			if(chosenMethod.equals(STACK_MIP)){
				statsImp = maximumProjection(selectedImp, 1, selectedImp.getStackSize());
			}
			out [i] = new ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result [] {
					ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.compute(statsImp, st, localMethod) };
			if(statsImp != selectedImp){
				statsImp.changes = false;
				statsImp.close();
			}
		}
		selectedImp.changes = false;
		selectedImp.close();
		System.gc();
		progress.addToBar(0.8/nGroups);
	}
	return out;
}

/**	@return a one-slice ImagePlus copy of the given slice, for per-slice statistics. */
private ImagePlus getSingleSlice(ImagePlus imp, int slice){
	ImageStack st = new ImageStack(imp.getWidth(), imp.getHeight());
	st.addSlice(imp.getStack().getProcessor(Math.min(slice, imp.getStackSize())).duplicate());
	ImagePlus out = new ImagePlus(imp.getTitle() + "_s" + slice, st);
	out.setCalibration(imp.getCalibration());
	return out;
}

/**
 * SegmentImage for a threshold SURFACE.
 *
 * Identical to segmentImage except that the comparison value is looked up per (x,y). Kept as a separate
 * method rather than overloading segmentImage with a nullable surface.
 */
private void segmentImageWithSurface(ImagePlus imp,
		ciliaQ_Prep_jnh.fraction_threshold.FractionThreshold.Result r, int z, ImagePlus impTemp,
		int zTemp){
	segmentImageWithSurface(imp, r.surface, r.width, r.global, z, impTemp, zTemp);
}

/**
 *	SegmentImage for a threshold SURFACE, shared by the LOCAL FRACTION and PHANSALKAR methods.
 *
 *	Takes the raw surface rather than either method's result type, so a second surface method needs no
 *	second apply path. A null surface falls back to the scalar, which is what the global FRACTION method
 *	uses.
 */
private void segmentImageWithSurface(ImagePlus imp, float [] surface, int surfaceWidth,
		double scalarThreshold, int z, ImagePlus impTemp, int zTemp){
	double maxValue = Math.pow(2.0, imp.getBitDepth()) - 1;
	for(int x = 0; x < imp.getWidth(); x++){
		for(int y = 0; y < imp.getHeight(); y++){
			double threshold = surface == null ? scalarThreshold : surface [y * surfaceWidth + x];
			double pxintensity = impTemp.getStack().getVoxel(x,y,zTemp);
			if(pxintensity < threshold){
				imp.getStack().setVoxel( x, y, z, 0.0);
			}else{
				countSegmentedVoxel(imp, x, y, z);		// zero-intensity loss warning
				if(keepIntensities == false){
					imp.getStack().setVoxel( x, y, z, maxValue);
				}
			}
		}
	}
}

/**
 * Determine thresholds as [time step][slice], so the per-slice option shares the apply loop.
 *
 * STACK_PER_SLICE needs one threshold per slice, whereas every other mode has only one per time step.
 * Instead of a second array the apply loop would have to branch on, every mode returns this 2D shape:
 * the per-slice mode fills the inner (slice) dimension; every other mode is delegated to getThresholds
 * and its one-per-time-step result is wrapped as a single inner entry, which thresholdAt() then
 * broadcasts across slices. One code path in the apply loop means the per-slice option cannot drift out
 * of step with the existing ones.
 *
 * @return thresholds[timeStep][slice]; the slice dimension is length 1 for every non-per-slice mode
 */
private double [][] getThresholdsPerSlice (ImagePlus imp, String chosenMethod, String chosenAlg,
		boolean seperateTimeSteps, ProgressReporter progress){
	// Every mode except per-slice already produces one threshold per time step: reuse getThresholds
	// and wrap each value as a 1-element slice row (thresholdAt broadcasts it to all slices).
	if(!chosenMethod.equals(STACK_PER_SLICE)){
		double [] flat = getThresholds(imp, chosenMethod, chosenAlg, seperateTimeSteps, progress);
		double [][] out = new double [flat.length][1];
		for(int i = 0; i < flat.length; i++)	out [i][0] = flat [i];
		return out;
	}
	
	// Per-slice: one group per frame when time steps are separate, otherwise a single pooled group.
	int startGroup = 1, endGroup = imp.getNFrames();
	int nGroups = seperateTimeSteps ? (endGroup - startGroup + 1) : 1;
	double [][] out = new double [nGroups][];
	for(int g = 0; g < nGroups; g++){
		// from..to selects this group's frame(s): one frame when separate, all frames when pooled.
		int from = seperateTimeSteps ? startGroup + g : startGroup;
		int to = seperateTimeSteps ? startGroup + g : endGroup;
		ImagePlus selectedImp = getSelectedTimepoints(imp, from, to, false);
		/*
		 * One threshold per SLICE. When time steps are pooled, every frame's slice s shares the
		 * threshold taken here from the first frame of the group - the same convention as the pooled
		 * whole-stack modes.
		 */
		int nSl = imp.getNSlices();
		out [g] = new double [nSl];
		for(int sl = 0; sl < nSl; sl++){
			if(keepAwake)	stayAwake();
			out [g][sl] = getSingleSliceImageThreshold(selectedImp, sl + 1, chosenAlg);
		}
		selectedImp.changes = false;
		selectedImp.close();
		System.gc();
		progress.addToBar(0.8/nGroups);
	}
	return out;
}

/**
 *	For methods that connect objects across slices, a per-slice threshold is not meaningful, so
 *	STACK_PER_SLICE is mapped to STACK_POOLED. Without this the value falls through to the "no stack"
 *	branch and the threshold would come from SLICE 1 ALONE - a silent and much worse answer than
 *	pooling. validateSettings warns the user that this substitution happened.
 */
private String stackMethodForStackWideMethod(String chosenMethod){
	return STACK_PER_SLICE.equals(chosenMethod) ? STACK_POOLED : chosenMethod;
}

/**	@return the threshold for one time step and slice, broadcasting whichever dimension is collapsed. */
private double thresholdAt(double [][] thr, int t, int s){
	double [] row = thr [thr.length == 1 ? 0 : Math.min(t, thr.length - 1)];
	return row [row.length == 1 ? 0 : Math.min(s, row.length - 1)];
}

/**
 * Determine one intensity threshold per time step, honouring the chosen stack-handling mode.
 *
 * Two layouts of the returned array:
 *  - separate time steps ON  -> one entry per frame, each thresholded from that frame alone;
 *  - separate time steps OFF -> a single entry (index 0) computed from all frames pooled.
 *
 * Within each group the stack-handling mode decides WHERE the threshold is read from:
 *  - STACK_POOLED -> the pooled histogram of the whole (multi-slice) stack;
 *  - STACK_MIP    -> the maximum-intensity projection, thresholded as one 2D image;
 *  - otherwise    -> the first slice only (the "no stack" case).
 * STACK_PER_SLICE is NOT handled here - it returns one value per slice and so goes through
 * getThresholdsPerSlice instead.
 *
 * @return thresholds indexed by time step (length 1 when time steps are pooled)
 */
private double [] getThresholds (ImagePlus imp, String chosenMethod, String chosenAlg, 
		boolean seperateTimeSteps, ProgressReporter progress){
	int startGroup = 1, endGroup = imp.getNFrames();
	ImagePlus selectedImp;
	double thresholds [] = {Double.NaN};
	if(seperateTimeSteps){
		thresholds = new double [endGroup-startGroup+1];
	}
	if(seperateTimeSteps){			
		for(int t = startGroup-1; t < endGroup; t++){
			// Threshold this one frame in isolation.
			selectedImp = getSelectedTimepoints(imp, t+1, t+1, false);
			if(chosenMethod.equals(STACK_POOLED)){
				thresholds [t-(startGroup-1)] = getHistogramThreshold(selectedImp, chosenAlg);
			}else if(chosenMethod.equals(STACK_MIP)){
				selectedImp = maximumProjection(selectedImp, 1, selectedImp.getStackSize());
				thresholds [t-(startGroup-1)] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);					
			}else{	// no stack: first slice only
				thresholds [t-(startGroup-1)] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);		
			}				
			selectedImp.changes = false;
			selectedImp.close();
			System.gc();
			progress.addToBar(0.8/(endGroup-startGroup+1));
		}
	}else{
		// One threshold from all frames pooled, stored at index 0.
		selectedImp = getSelectedTimepoints(imp, startGroup, endGroup, false);
		if(chosenMethod.equals(STACK_POOLED)){
			thresholds [0] = getHistogramThreshold(selectedImp, chosenAlg);
		}else if(chosenMethod.equals(STACK_MIP)){
			selectedImp = maximumProjection(selectedImp, 1, selectedImp.getStackSize());
			thresholds [0] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);	
		}else{	// no stack: first slice only
			thresholds [0] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);	
		}		
		progress.addToBar(0.8);
		selectedImp.changes = false;
		selectedImp.close();			
		System.gc();
	}
	return thresholds;
}

/**
 * New V0.2.1: Write mask metrics and warnings into `_CQP.txt`, so they can be found also later
 * @param procImp the segmented output, @param origImp the unmodified input
 */
private void reportMaskMetrics(ImagePlus procImp, ImagePlus origImp, TextCollector tp1,
		ProgressReporter progress, int task, int tasks){
	tp1.append("");
	tp1.append("Mask metrics per segmented channel");
	tp1.append("	Channel	Total voxels	Foreground voxels	Foreground %	Non-zero voxels in input");
	for(int c = 0; c < channelIDs.length; c++){
		if(keepAwake)	stayAwake();
		if(channelIDs [c] < 1 || channelIDs [c] > procImp.getNChannels())	continue;
		long total = 0, fg = 0, origNonZero = 0;
		for(int t = 0; t < procImp.getNFrames(); t++){
			for(int sl = 0; sl < procImp.getNSlices(); sl++){
				int iOut = procImp.getStackIndex(channelIDs [c], sl+1, t+1)-1;
				int iIn = origImp.getStackIndex(channelIDs [c], sl+1, t+1)-1;
				ij.process.ImageProcessor pOut = procImp.getStack().getProcessor(iOut+1);
				ij.process.ImageProcessor pIn = origImp.getStack().getProcessor(iIn+1);
				int n = procImp.getWidth() * procImp.getHeight();
				total += n;
				for(int i = 0; i < n; i++){
					if(pOut.getf(i) != 0f)	fg++;
					if(pIn.getf(i) != 0f)	origNonZero++;
				}
			}
		}
		double pct = total > 0 ? (100.0 * fg / total) : 0.0;
		tp1.append("	" + df0.format(channelIDs [c]) + "	" + total + "	" + fg + "	"
				+ df3.format(pct) + "	" + origNonZero);

		/*
		 * ZERO-INTENSITY LOSS. Written for every channel that kept intensities, so the loss is visible
		 * in the archived record.
		 */
		if(maskVoxelCount != null && c < maskVoxelCount.length && maskVoxelCount [c] > 0){
			long lost = zeroIntensityVoxelCount [c];
			double lostPct = 100.0 * lost / maskVoxelCount [c];
			tp1.append("		Segmented voxels with zero intensity:	" + lost + " of "
					+ maskVoxelCount [c] + " (" + df3.format(lostPct) + "%)");
			if(lost > 0){
				progress.notifyMessage("Task " + (task+1) + "/" + tasks + ", channel " + channelIDs [c]
					+ ": " + lost + " of " + maskVoxelCount [c] + " segmented voxels ("
					+ df0.format(lostPct) + " %) have an original intensity of 0 and are therefore"
					+ " written as 0. CiliaQ treats only voxels > 0 as foreground, so it will see a"
					+ " smaller object than the segmentation found. This happens when a blur or another"
					+ " filter extends the mask over background that is exactly 0. Choose a binary"
					+ " segmentation style to keep them.", ProgressReporter.NOTIFICATION);
				tp1.append("		WARNING	" + lost + " segmented voxels are written as 0 because their"
						+ " original intensity is 0 - choose a binary segmentation style to keep them");
			}
		}

		/*	The channel had nothing in it to begin with. Reported separately from D4e because the cause
		 *	and the fix are different: this is a wrong channel number or an empty acquisition. */
		if(origNonZero == 0){
			progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": channel " + channelIDs [c]
				+ " of the input is entirely zero, so the segmented output is empty too."
				+ " Check that the channel number is right.", ProgressReporter.NOTIFICATION);
			tp1.append("	WARNING	channel " + channelIDs [c] + " of the input is entirely zero");
			continue;
		}

		/*	Only meaningful for the methods that actually apply an intensity threshold: the three
		 *	label/detector methods select structures rather than thresholding, so "removed nothing" says
		 *	nothing about them. */
		boolean thresholdMethod = !(RIDGE_DETECTION.equals(chosenAlgorithms [c])
				|| SPOT_DETECTION.equals(chosenAlgorithms [c])
				|| EXPANSION_SEEDS.equals(chosenAlgorithms [c]));
		if(thresholdMethod && fg == origNonZero){
			progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": the threshold for channel "
				+ channelIDs [c] + " removed 0 voxels - every non-zero voxel of the input passed it,"
				+ " so the segmented channel is identical to the unsegmented one."
				+ " The threshold is at or below the lowest non-zero intensity.",
				ProgressReporter.NOTIFICATION);
			tp1.append("	WARNING	the threshold removed 0 voxels - output identical to the input");
		}else if(thresholdMethod && fg == 0){
			progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": the threshold for channel "
				+ channelIDs [c] + " removed EVERYTHING - no voxel passed it, so the segmented channel"
				+ " is empty. The threshold is above the highest intensity.",
				ProgressReporter.NOTIFICATION);
			tp1.append("	WARNING	the threshold removed every voxel - output is empty");
		}
	}
	tp1.append("");
}

/**
 *	2D median filter, per slice, in place.
 *
 *	ij.plugin.filter.RankFilters with MEDIAN. 2D per slice is sufficient: the noise being removed
 *	is per-pixel detector noise, which is not correlated in z, and a 3D median on a strongly anisotropic
 *	stack would mix voxels that are far apart in real space.
 */
private void applyMedianFilter(ImagePlus imp, double radius){
	ij.plugin.filter.RankFilters rf = new ij.plugin.filter.RankFilters();
	for(int s = 0; s < imp.getStackSize(); s++){
		if(keepAwake)	stayAwake();
		rf.rank(imp.getStack().getProcessor(s+1), radius, ij.plugin.filter.RankFilters.MEDIAN);
	}
}

/**
 * SPOT DETECTION (multi-scale DoG plus MaximumFinder), for punctate structures.
 *
 * Writes ONE VOXEL per detected spot, binary. A basal body needs no extent: only its centre is used
 * downstream, from which every reported value is derived, so a single voxel carries everything needed.
 *
 * Unlike ridge detection this does NOT need to promote the output image.
 */
private void segmentUsingSpotDetection(ImagePlus channelImp, ProgressReporter progress,
		ciliaQ_Prep_jnh.spot_detection.SpotSettings ss, ImagePlus writeImp, int channelWriteImp,
		TextCollector tp1) throws Exception {

	tp1.append("Spot detection for channel " + channelWriteImp);
	tp1.append(ss.report().replaceAll("\n$", ""));

	/*
	 * The dialog could not know the bit depth (no image was open when it ran), so the sanity check on
	 * sensitivity happens here instead. Sensitivity is in ABSOLUTE grey levels by design.
	 */
	String warn = ss.sensitivityWarning(channelImp.getBitDepth());
	if(warn != null){
		progress.notifyMessage("Channel " + channelWriteImp + ": " + warn, ProgressReporter.NOTIFICATION);
		tp1.append("	WARNING	" + warn);
	}

	double maxValue = Math.pow(2.0, writeImp.getBitDepth()) - 1.0;
	int totalSpots = 0, totalRejected = 0;
	for(int t = 0; t < channelImp.getNFrames(); t++){
		if(keepAwake) stayAwake();
		progress.updateBarText("Spot detection on channel " + channelWriteImp + ", frame " + (t+1) + " ...");
		ImagePlus selectedImp = getSelectedTimepoints(channelImp, t+1, t+1, false);
		ciliaQ_Prep_jnh.spot_detection.SpotDetector.Result r =
				ciliaQ_Prep_jnh.spot_detection.SpotDetector.detect(selectedImp, ss);
		/* Clear first: the output channel still holds the copied original intensities. */
		ciliaQ_Prep_jnh.spot_detection.SpotDetector.clearChannel(writeImp, channelWriteImp, t,
				selectedImp.getNSlices());
		ciliaQ_Prep_jnh.spot_detection.SpotDetector.writeSpots(r, writeImp, channelWriteImp, t, maxValue);
		totalSpots += r.spots.size();
		totalRejected += r.rejectedByIntensity;
		if(t == 0){
			tp1.append("	Minimum spot intensity used	" + df3.format(r.minIntensityUsed));
			tp1.append("	Spots before z-consolidation (frame 1)	" + r.beforeZConsolidation);
			tp1.append("	Spots after z-consolidation (frame 1)	" + r.spots.size());
		}
		selectedImp.changes = false;
		selectedImp.close();
	}
	tp1.append("	Spots detected (all frames)	" + totalSpots);
	tp1.append("	Spots rejected by minimum intensity	" + totalRejected);
	if(totalSpots == 0){
		progress.notifyMessage("Channel " + channelWriteImp + ": spot detection found NO spots."
				+ " Lower the sensitivity, or check that the channel carries punctate signal.",
				ProgressReporter.NOTIFICATION);
	}
}

/**
 * EXPANSION FROM SEEDS producing a LABEL image.
 * 
 * This expansion tool can work for both LoG seeds or any other high threshold seed (hysteresis kind of seeding)".
 * So there is ONE growth engine and the seed source is a user choice - ridge lines for filaments,
 * spot maxima for punctate structures, high-intensity cores as the fallback that needs no extra detector.
 *
 * Like ridge detection it RETURNS the image to use from here on (writing labels may require promoting
 * the output image) and it ignores keepIntensities, because a voxel cannot carry both its intensity and
 * its object ID. Both are reported rather than silently applied.
 *
 * TOUCHING objects separate, CROSSING objects do not and are left whole on purpose
 * by the minimum-piece guard. See SeededGrowth's class comment.
 */
private ImagePlus segmentUsingExpansion(ImagePlus channelImp, ProgressReporter progress,
		ciliaQ_Prep_jnh.expansion.ExpansionSettings es, ImagePlus writeImp, int channelWriteImp,
		int channelIndex, int channelArrayIndex, TextCollector tp1) throws Exception {

	tp1.append("Expansion from seeds for channel " + channelWriteImp);
	tp1.append(es.report().replaceAll("\n$", ""));

	String warn = es.seedSourceWarning();
	if(warn != null){
		progress.notifyMessage("Channel " + channelWriteImp + ": " + warn, ProgressReporter.NOTIFICATION);
		tp1.append("	WARNING	" + warn);
	}
	if(keepIntensities){
		progress.notifyMessage("Channel " + channelWriteImp + ": expansion writes a LABEL image, so"
				+ " 'keep intensities' cannot apply to this channel and was ignored for it."
				+ " Other channels are unaffected.", ProgressReporter.NOTIFICATION);
		tp1.append("	NOTE	keep-intensities ignored for this channel (label output)");
	}

	/*
	 * The two thresholds go through getThresholds, so they honour the SAME stack-processing choice
	 * (whole stack histogram / maximum projection / per slice) and the same separate-time-steps choice
	 * as every other method. Custom values bypass it, exactly as CUSTOM threshold does.
	 */
	int nFrames = channelImp.getNFrames();
	double [] lowThr;
	if(es.customValueForLowThreshold()){
		lowThr = new double [nFrames];
		Arrays.fill(lowThr, es.customLowThr);
	}else{
		lowThr = getThresholds(channelImp,
				stackMethodForStackWideMethod(chosenStackMethods [channelArrayIndex]),
				es.lowThrAlgorithm, separateTimesteps [channelArrayIndex], progress);
	}
	double [] seedThr = null;
	if(es.seedSource.equals(ciliaQ_Prep_jnh.expansion.ExpansionSettings.SEED_HIGH)){
		if(es.customValueForSeedThreshold()){
			seedThr = new double [nFrames];
			Arrays.fill(seedThr, es.customSeedThr);
		}else{
			seedThr = getThresholds(channelImp,
					stackMethodForStackWideMethod(chosenStackMethods [channelArrayIndex]),
					es.seedThrAlgorithm, separateTimesteps [channelArrayIndex], progress);
		}
	}

	int [][] labelsPerFrame = new int [nFrames][];
	int maxLabels = 0;
	for(int t = 0; t < nFrames; t++){
		if(keepAwake) stayAwake();
		progress.updateBarText("Expansion on channel " + channelWriteImp + ", frame " + (t+1) + " ...");
		ImagePlus selectedImp = getSelectedTimepoints(channelImp, t+1, t+1, false);
		int W = selectedImp.getWidth(), H = selectedImp.getHeight(), D = selectedImp.getStackSize();
		ciliaQ_Prep_jnh.expansion.SeededGrowth sg =
				new ciliaQ_Prep_jnh.expansion.SeededGrowth(W, H, D);

		double low = lowThr [lowThr.length == 1 ? 0 : t];
		boolean [] mask = sg.mask(selectedImp, low);

		int [] seeds;
		if(es.seedSource.equals(ciliaQ_Prep_jnh.expansion.ExpansionSettings.SEED_RIDGE)){
			ciliaQ_Prep_jnh.ridge_detection.RidgeSettings rs = es.toRidgeSettings();
			double [] st = medianAndHighPercentile(selectedImp);
			rs.deriveFrom(st [0], st [1]);
			String reject = rs.rejectionReason(st [2]);
			if(reject != null) throw new Exception("Expansion seed detection: " + reject);
			ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.Result rr =
					ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.detect(selectedImp, rs, progress);
			seeds = sg.seedsFromRidges(rr, mask);
		}else if(es.seedSource.equals(ciliaQ_Prep_jnh.expansion.ExpansionSettings.SEED_SPOT)){
			seeds = sg.seedsFromSpots(selectedImp, mask, es.toSpotSettings());
		}else{
			seeds = sg.seedsFromHighThreshold(selectedImp, mask,
					seedThr [seedThr.length == 1 ? 0 : t]);
		}

		ciliaQ_Prep_jnh.expansion.SeededGrowth.Result r =
				sg.grow(mask, seeds, es.minPieceSize, true);
		int nLab = sg.renumber(r.labels);
		labelsPerFrame [t] = r.labels;
		maxLabels = Math.max(maxLabels, nLab);

		if(nFrames > 1)		tp1.append("	Frame " + (t+1));
		tp1.append("		Threshold for the object extent	" + df3.format(low));
		if(seedThr != null)
			tp1.append("		Seed threshold	" + df3.format(seedThr [seedThr.length == 1 ? 0 : t]));
		tp1.append("		Seeds	" + r.nSeeds);
		tp1.append("		Objects before splitting	" + r.nComponents);
		tp1.append("		Objects split	" + r.nSplit);
		tp1.append("		Splits undone by the minimum-size guard	" + r.nReverted);
		tp1.append("		Objects in the label image	" + nLab);
		/*
		 * Discarding is REPORTED, never silent: with the classic-hysteresis behaviour a low-mask
		 * component holding no seed is dropped, and a user must be able to see how much that was.
		 */
		if(r.nUnseeded > 0)
			tp1.append("		Objects discarded for containing no seed	" + r.nUnseeded
					+ "	(" + r.unseededVoxels + " voxels)");

		selectedImp.changes = false;
		selectedImp.close();
	}
	tp1.append("	Objects in the label image (max over frames)	" + maxLabels);
	tp1.append("	Labels will be written after all channels are segmented (bit depth decided then)");

	pendingExpansionLabels [channelIndex] = labelsPerFrame;
	pendingExpansionChannel [channelIndex] = channelWriteImp;
	pendingExpansionMax [channelIndex] = maxLabels;
	return writeImp;
}

/**
 * Median, 99.9th percentile and MAD of a whole stack, as ridge detection needs them.
 * Factored out of segmentUsingRidgeDetection so the expansion tool derives its ridge seed thresholds
 * from exactly the same statistics - two copies would drift apart.
 *
 * @return { median, p99.9, MAD }
 */
private double [] medianAndHighPercentile(ImagePlus channelImp){
	int nVox = channelImp.getWidth() * channelImp.getHeight() * channelImp.getStackSize();
	float [] all = new float [nVox];
	int k = 0;
	for(int s = 0; s < channelImp.getStackSize(); s++){
		ij.process.ImageProcessor ip = channelImp.getStack().getProcessor(s+1);
		for(int i = 0; i < channelImp.getWidth() * channelImp.getHeight(); i++) all [k++] = ip.getf(i);
	}
	java.util.Arrays.sort(all);
	double median = all [nVox/2];
	double p999 = all [(int)(nVox * 0.999)];
	double [] dev = new double [nVox];
	for(int i = 0; i < nVox; i++) dev [i] = Math.abs(all [i] - median);
	java.util.Arrays.sort(dev);
	double mad = dev [nVox/2];
	return new double [] { median, p999, mad };
}

/**
 * RIDGE DETECTION (Steger line detector) producing a LABEL image.
 *
 * Differs from the other segmentUsing* methods in two ways, both unavoidable:
 *  - it RETURNS the image to use from here on, because writing labels may require promoting the output
 *    image to a deeper bit depth, and the caller must pick up the promoted reference;
 *  - it ignores keepIntensities, because a voxel cannot carry both its intensity and its object ID.
 *    That is reported rather than silently applied.
 *
 * The thresholds are derived HERE, from stack-wide statistics, and not in the settings dialog: a dialog
 * listener never fires in macro or headless mode, so deriving there would give every optimizer run
 * unconverted defaults.
 */
private ImagePlus segmentUsingRidgeDetection(ImagePlus channelImp, ProgressReporter progress,
		ciliaQ_Prep_jnh.ridge_detection.RidgeSettings rs, ImagePlus writeImp, int channelWriteImp,
		int channelIndex, TextCollector tp1) throws Exception {

	/*
	 * Stack-wide statistics, computed ONCE. Deriving per slice made line counts vary by two orders of
	 * magnitude between adjacent planes, which is threshold inconsistency rather than structure and
	 * breaks z-linking.
	 */
	int nVox = channelImp.getWidth() * channelImp.getHeight() * channelImp.getStackSize();
	float [] all = new float [nVox];
	int k = 0;
	for(int s = 0; s < channelImp.getStackSize(); s++){
		ij.process.ImageProcessor ip = channelImp.getStack().getProcessor(s+1);
		for(int i = 0; i < channelImp.getWidth() * channelImp.getHeight(); i++) all [k++] = ip.getf(i);
	}
	java.util.Arrays.sort(all);
	double median = all [nVox/2];
	double p999 = all [(int)(nVox * 0.999)];
	double [] dev = new double [nVox];
	for(int i = 0; i < nVox; i++) dev [i] = Math.abs(all [i] - median);
	java.util.Arrays.sort(dev);
	double mad = dev [nVox/2];

	rs.deriveFrom(median, p999);
	String reject = rs.rejectionReason(mad);
	if(reject != null) throw new Exception(reject);
	
	// Pre-flight: a near-zero upper threshold means the detector will trace noise and can hang. The
	// MAD guard above misses this on background-free data (MAD = 0), so catch it here, before detection.
	if(rs.upperThreshold <= 1e-6){
		throw new Exception("ridge detection: the derived upper threshold is ~0 for this channel"
				+ " (median " + df3.format(median) + ", MAD " + df3.format(mad) + "), so the detector"
				+ " may trace noise and may not finish. If so, raise 'contrast sensitivity', or check whether"
				+ " the channel carries filamentous signal.");
	}
	
	tp1.append("Ridge detection for channel " + channelWriteImp);
	tp1.append("	Channel median	" + df3.format(median));
	tp1.append("	Channel p99.9	" + df3.format(p999));
	tp1.append("	Channel MAD	" + df3.format(mad));
	tp1.append(rs.report().replaceAll("\n$", ""));

	if(keepIntensities){
		progress.notifyMessage("Channel " + channelWriteImp + ": ridge detection writes a LABEL image, so"
				+ " 'keep intensities' cannot apply to this channel and was ignored for it."
				+ " Other channels are unaffected.", ProgressReporter.NOTIFICATION);
		tp1.append("	NOTE	keep-intensities ignored for this channel (label output)");
	}

	/*
	 * Detect every timepoint into scratch arrays FIRST, so the required bit depth is known before
	 * anything is written. Mirrors how the rest of the plugin computes before it writes.
	 */
	ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.Result [] res =
			new ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.Result [channelImp.getNFrames()];
	int maxObjects = 0;
	boolean anyWrapped = false;
	for(int t = 0; t < channelImp.getNFrames(); t++){
		if(keepAwake) stayAwake();
		progress.updateBarText("Ridge detection on channel " + channelWriteImp + ", frame " + (t+1) + " ...");
		ImagePlus selectedImp = getSelectedTimepoints(channelImp, t+1, t+1, false);
		res [t] = ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.detect(selectedImp, rs, progress);
		maxObjects = Math.max(maxObjects, res [t].nObjects);
		anyWrapped = anyWrapped || res [t].wrapped;
		selectedImp.changes = false;
		selectedImp.close();
	}
	tp1.append("	Detected filaments (max over frames)	" + maxObjects);
	if(anyWrapped){
		progress.notifyMessage("Channel " + channelWriteImp + ": more than " 
				+ ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.MAX_LABEL
				+ " objects - label numbering wrapped, so some labels are DUPLICATED.",
				ProgressReporter.NOTIFICATION);
		tp1.append("	WARNING	label numbering wrapped - duplicated labels");
	}

	/*
	 * DO NOT promote or write here. Both are deferred to finishPendingRidgeLabels(), called once after
	 * every channel has been segmented -- see the pendingRidgeLabels field for why.
	 */
	tp1.append("	Labels will be written after all channels are segmented (bit depth decided then)");
	pendingRidgeLabels [channelIndex] = res;
	pendingRidgeChannel [channelIndex] = channelWriteImp;
	return writeImp;
}

/**
 * Apply ONE promotion for all ridge channels, then write every stashed label set, then set the LUTs.
 *
 * Called once, after the whole channel loop. This is what makes promotion safe: it cannot discard work
 * or display state belonging to another channel, because nothing has been written yet and no other
 * channel is processed afterwards.
 *
 * @return the image to use from here on, promoted if any channel needed it.
 */
private ImagePlus finishPendingLabels(ImagePlus writeImp, ProgressReporter progress,
		TextCollector tp1){
	/*
	 * BOTH label-producing methods (Ridge detection, GROW expansion) are finished here, in ONE
	 * pass, because the bit depth has to be decided from the largest label count across all of them.
	 * Finishing them separately would promote twice, and the second promotion would replace the
	 * ImagePlus that the first had already written labels into.
	 */
	int maxObjectsAll = 0;
	boolean any = false;
	if(pendingRidgeLabels != null){
		for(int i = 0; i < pendingRidgeLabels.length; i++){
			if(pendingRidgeLabels [i] == null) continue;
			any = true;
			for(int t = 0; t < pendingRidgeLabels [i].length; t++){
				maxObjectsAll = Math.max(maxObjectsAll, pendingRidgeLabels [i][t].nObjects);
			}
		}
	}
	if(pendingExpansionLabels != null){
		for(int i = 0; i < pendingExpansionLabels.length; i++){
			if(pendingExpansionLabels [i] == null) continue;
			any = true;
			maxObjectsAll = Math.max(maxObjectsAll, pendingExpansionMax [i]);
		}
	}
	if(!any) return writeImp;

	int needed = Math.max(writeImp.getBitDepth(),
			ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.requiredBitDepth(maxObjectsAll));
	if(needed > writeImp.getBitDepth()){
		progress.updateBarText("Promoting output image to " + needed + "-bit to fit "
				+ maxObjectsAll + " labels ...");
		tp1.append("Output image promoted from " + writeImp.getBitDepth() + "-bit to " + needed
				+ "-bit (once, for all label-producing channels)");
		writeImp = ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.promoteKeepingValues(writeImp, needed);
	}else{
		tp1.append("Output image bit depth unchanged (" + writeImp.getBitDepth() + "-bit)");
	}

	if(pendingRidgeLabels != null){
		for(int i = 0; i < pendingRidgeLabels.length; i++){
			if(pendingRidgeLabels [i] == null) continue;
			int chMax = 0;
			for(int t = 0; t < pendingRidgeLabels [i].length; t++){
				if(keepAwake) stayAwake();
				ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.writeLabels(
						pendingRidgeLabels [i][t], writeImp, pendingRidgeChannel [i], t);
				chMax = Math.max(chMax, pendingRidgeLabels [i][t].nObjects);
			}
			ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.applyGlasbeyLut(
					writeImp, pendingRidgeChannel [i]);
			// Stretch display to 0..nLabels so the Glasbey colours are visible (labels 1..N would
			// otherwise sit near-black against the full bit-depth range). Display only; values untouched.
			writeImp.setC(pendingRidgeChannel [i]);
			writeImp.setDisplayRange(0, Math.max(1, chMax));
		}
	}
	if(pendingExpansionLabels != null){
		for(int i = 0; i < pendingExpansionLabels.length; i++){
			if(pendingExpansionLabels [i] == null) continue;
			for(int t = 0; t < pendingExpansionLabels [i].length; t++){
				if(keepAwake) stayAwake();
				ciliaQ_Prep_jnh.expansion.SeededGrowth.writeLabels(pendingExpansionLabels [i][t],
						writeImp.getWidth(), writeImp.getHeight(), writeImp.getNSlices(),
						writeImp, pendingExpansionChannel [i], t);
			}
			ciliaQ_Prep_jnh.ridge_detection.RidgeSegmenter.applyGlasbeyLut(
					writeImp, pendingExpansionChannel [i]);
			writeImp.setC(pendingExpansionChannel [i]);
			writeImp.setDisplayRange(0, Math.max(1, pendingExpansionMax [i]));
		}
	}
	return writeImp;
}

/** @return the 3D ImageJ Suite (mcib3d) version from its JAR manifest, else the jar filename, else
 *  "unknown". Used by Canny 3D and Hysteresis thresholding, whose results can change between suite
 *  versions. Provenance only - never fails a run. */
private static String mcib3dVersion(){
	try {
		Class<?> c = Class.forName("mcib3d.image3d.ImageInt");
		Package p = c.getPackage();
		if(p != null && p.getImplementationVersion() != null)
			return p.getImplementationVersion();
		java.net.URL loc = c.getProtectionDomain().getCodeSource().getLocation();
		if(loc != null)
			return new java.io.File(loc.toURI()).getName();	// e.g. "mcib3d-core-4.1.5.jar"
	} catch(Throwable t){ /* not installed or inaccessible - provenance only */ }
	return "unknown";
}

/**	
 *	Segment image using CANNY 3D method 
 * */
private void segmentUsingCanny3D(ImagePlus channelImp, ProgressReporter progress, ProcessSettings settings, ImagePlus writeImp, int channelWriteImp){
	ImagePlus selectedImp;
	int indexSelected, indexWriteImp;
	double maxValue = Math.pow(2.0, writeImp.getBitDepth()) - 1;
	for(int t = 0; t < channelImp.getNFrames(); t++){
		if(keepAwake) {
			stayAwake();
		}
		
		/*
		 * Extract single time point and 
		 * */
		selectedImp = getSelectedTimepoints(channelImp, t+1, t+1, false);	
		
		if(keepAwake) {
			stayAwake();
		}
		
		/*
		 * Convert using the Canny 3D plugin by S. Rassmann: 
		 * https://github.com/sRassmann/canny3d-thresholder, v0.1.0
		 * For details see ciliaQ_Prep_jnh.canny3d_thresholder.Processing
		 * */			
		selectedImp = ciliaQ_Prep_jnh.canny3d_thresholder.Processing.doProcessing(selectedImp, settings, progress);
		
		/*
		 * Write back to image
		 * */		
		for(int s = 0; s < selectedImp.getNSlices(); s++){
			if(keepAwake) {
				stayAwake();
			}
			indexSelected = selectedImp.getStackIndex(1, s+1, 1)-1;
			indexWriteImp = writeImp.getStackIndex(channelWriteImp, s+1, t+1)-1;
			for(int x = 0; x < selectedImp.getWidth(); x++){
				for(int y = 0; y < selectedImp.getHeight(); y++){
					if(selectedImp.getStack().getVoxel(x, y, indexSelected) == 0.0){
						writeImp.getStack().setVoxel(x, y, indexWriteImp, 0.0);
					}else if(!keepIntensities){
						writeImp.getStack().setVoxel(x, y, indexWriteImp, maxValue);
					}
				}
			}
		}
		
		selectedImp.changes = false;
		selectedImp.close();
		System.gc();
		progress.addToBar(0.8/(double)(channelImp.getNFrames()));
	}
}

/**	
 *	Segment image using Hysteresis threshold 
 * */
private void segmentUsingHysteresis(ImagePlus channelImp, ProgressReporter progress, double thresholds [][], ImagePlus writeImp, int channelWriteImp){
	ImagePlus selectedImp;
	int indexSelected, indexWriteImp;
	double maxValue = Math.pow(2.0, writeImp.getBitDepth()) - 1;
	for(int t = 0; t < channelImp.getNFrames(); t++){
		if(keepAwake) {
			stayAwake();
		}
		
		/*
		 * Extract single time point and 
		 * */
		selectedImp = getSelectedTimepoints(channelImp, t+1, t+1, false);	
		
		/*
		 * Convert to bin
		 * */			
		selectedImp = doHysteresisThreshold(selectedImp, progress, thresholds [t]);
		
		/*
		 * Write back to image
		 * */		
		for(int s = 0; s < selectedImp.getNSlices(); s++){
			if(keepAwake) {
				stayAwake();
			}
			indexSelected = selectedImp.getStackIndex(1, s+1, 1)-1;
			indexWriteImp = writeImp.getStackIndex(channelWriteImp, s+1, t+1)-1;
			for(int x = 0; x < selectedImp.getWidth(); x++){
				for(int y = 0; y < selectedImp.getHeight(); y++){
					if(selectedImp.getStack().getVoxel(x, y, indexSelected) == 0.0){
						writeImp.getStack().setVoxel(x, y, indexWriteImp, 0.0);
					}else if(!keepIntensities){
						writeImp.getStack().setVoxel(x, y, indexWriteImp, maxValue);
					}
				}
			}
		}
		
		selectedImp.changes = false;
		selectedImp.close();
		System.gc();
		progress.addToBar(0.8/(double)(channelImp.getNFrames()));
	}
}

/**
 * Perform a hysteresis threshold on a single image.
 * From V0.2.0: runs the 3D-Suite command in batch mode so no window is displayed.
 * */
private ImagePlus doHysteresisThreshold(ImagePlus imp, ProgressReporter pD, double thresholds []) {
	pD.updateBarText("Hysteresis threshold - segmentation running");

	boolean previousBatch = ij.macro.Interpreter.isBatchMode();
	ij.macro.Interpreter.batchMode = true;
	ImagePlus bin = null;
	try{
		imp.show();
		IJ.run(imp, "3D Hysteresis Thresholding", "high=" + thresholds [1] + " low=" + thresholds [0]);
		bin = ij.macro.Interpreter.getLastBatchModeImage();
		if(bin == null || bin == imp){
			throw new RuntimeException("3D Hysteresis Thresholding returned no image"
					+ " - is the 3D ImageJ Suite installed?");
		}
		imp.hide();
		ij.macro.Interpreter.removeBatchModeImage(bin);
		return bin;
	}catch(RuntimeException e){
		if(bin != null){ bin.changes = false; bin.close(); }
		throw e;
	}finally{
		ij.macro.Interpreter.batchMode = previousBatch;
	}
}

/*
 * 
 * */
private double [][] getHysteresisThresholds (ImagePlus imp, int settingsID, boolean seperateTimeSteps, ProgressReporter progress){
	int startGroup = 1, endGroup = imp.getNFrames();
	ImagePlus selectedImp;
//	double thresholds [][] = new double [1][2]; // first dim: image, 2nd dim: 0 = low, 1 = high
//	Arrays.fill(thresholds[0],Double.NaN);
	
//	if(seperateTimeSteps){
//		thresholds = new double [endGroup-startGroup+1][2];
//	}
	double thresholds [][] = new double [endGroup-startGroup+1][2];
	
	String chosenAlg = "";
	for(int i = 0; i < 2; i++) {
		if(i == 0) {
			chosenAlg = chosenLowAlg[settingsID];
			if(chosenAlg.equals(hystAlgorithms[0])) { // CUSTOM THR
				for(int t = startGroup-1; t < endGroup; t++){
					thresholds [t-(startGroup-1)][i] = customLowThr [settingsID];
				}
				continue;
			}
		}else if(i == 1) {
			chosenAlg = chosenHighAlg[settingsID];
			if(chosenAlg.equals(hystAlgorithms[0])) { // CUSTOM THR
				for(int t = startGroup-1; t < endGroup; t++){
					thresholds [t-(startGroup-1)][i] = customHighThr [settingsID];
				}
				continue;
			}
		}
		
		if(seperateTimeSteps){			
			for(int t = startGroup-1; t < endGroup; t++){
				selectedImp = getSelectedTimepoints(imp, t+1, t+1, false);
				if(stackMethodForStackWideMethod(chosenStackMethods[settingsID]).equals(STACK_POOLED)){
					thresholds [t-(startGroup-1)][i] = getHistogramThreshold(selectedImp, chosenAlg);
				}else if(chosenStackMethods[settingsID].equals(STACK_MIP)){
					selectedImp = maximumProjection(selectedImp, 1, selectedImp.getStackSize());
					thresholds [t-(startGroup-1)][i] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);					
				}else{
					thresholds [t-(startGroup-1)][i] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);		
				}				
				selectedImp.changes = false;
				selectedImp.close();
				System.gc();
				progress.addToBar(0.8/(endGroup-startGroup+1));
			}
		}else{
			selectedImp = getSelectedTimepoints(imp, startGroup, endGroup, false);
			if(stackMethodForStackWideMethod(chosenStackMethods[settingsID]).equals(STACK_POOLED)){
				thresholds [0][i] = getHistogramThreshold(selectedImp, chosenAlg);
			}else if(chosenStackMethods[settingsID].equals(STACK_MIP)){
				selectedImp = maximumProjection(selectedImp, 1, selectedImp.getStackSize());
				thresholds [0][i] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);	
			}else{
				thresholds [0][i] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);	
			}
			for(int t = 0; t < thresholds.length; t++){
				thresholds [t][i] = thresholds [0][i];				
			}
			
			progress.addToBar(0.8);
			selectedImp.changes = false;
			selectedImp.close();			
			System.gc();
		}
	}
	return thresholds;
}

/**
 * Hysteresis thresholding needs the low threshold below the high one. Whether the two chosen
 * METHODS differ is checked once in validateSettings, but the resulting VALUES are only known
 * here, per image: an auto-threshold algorithm that returns 0 - which happens on sparse images,
 * where most voxels are exact zeros - inverts the pair. Hysteresis then cannot grow anything and
 * silently degenerates into a plain threshold at the low value, which looks like a successful
 * segmentation in the output image. So report it while the numbers are known.
 * */
private void warnOnHysteresisThresholdOrder(double thresholds [][], int channel, int task){
	String offending = "";
	int affected = 0;
	for(int t = 0; t < thresholds.length; t++){
		if(!(thresholds [t][0] < thresholds [t][1])){		// also catches NaN and low == high
			affected++;
			if(affected <= 3){
				offending += (offending.isEmpty() ? "" : ", ")
					+ "time step " + (t+1) + ": low " + df3.format(thresholds [t][0])
					+ " >= high " + df3.format(thresholds [t][1]);
			}
		}
	}
	if(affected == 0)	return;

	progress.notifyMessage("Task " + (task+1) + "/" + tasks + ", channel " + channel
		+ ": the hysteresis low threshold is not below the high threshold"
		+ "\n(" + offending
		+ (affected > 3 ? ", and " + (affected - 3) + " more time step(s)" : "") + ")."
		+ "\nHysteresis keeps the regions above the low threshold that contain a voxel above the"
		+ "\nhigh threshold. With the pair inverted there is nothing left to grow, so the result"
		+ "\nis simply everything above the low threshold - and if that threshold is also near"
		+ "\nzero, the whole image. If the threshold is near zero or zero, the output will look"
		+ "\nsegmented but is not. Choose a more inclusive method for the low threshold and a stricter"
		+ "\none for the high threshold.",
		ProgressReporter.NOTIFICATION);
}

/**
 * @return maximum-intensity-projection image of the specified stack range in the input ImagePlus (imp)
 * startSlice = first slice included into projection (1 < startSlice < NSlices)
 * endSlice = last slice included into projection (1 < endSlice < NSlices)
 * */
private ImagePlus maximumProjection(ImagePlus imp, int startSlice, int endSlice){
	//reset borders, if indicated start / end does not fit stack size
	if(startSlice < 1)	startSlice=1;
	if(endSlice > imp.getStackSize())	endSlice = imp.getStackSize();
	
	//generate maximum intensity projection
	ImagePlus outImp = IJ.createImage("MIP", imp.getWidth(), imp.getHeight(), 1, imp.getBitDepth());
	double maximumAtPos;
	for(int x = 0; x < imp.getWidth(); x++){
		for(int y = 0; y < imp.getHeight(); y++){
			maximumAtPos = 0.0;
			for(int z = startSlice-1; z < endSlice; z++){
				if(imp.getStack().getVoxel(x,y,z) > maximumAtPos){
					maximumAtPos = imp.getStack().getVoxel(x,y,z);
				}
			}
			outImp.getStack().setVoxel(x,y,0,maximumAtPos);
		}
	}
	outImp.setCalibration(imp.getCalibration());
	return outImp;
}


/**
 * @return a threshold for the slice image <s> in the ImagePlus <parImp> for the image <imp>
 * range: 1 <= z <= stacksize
 * */
private double getSingleSliceImageThreshold (ImagePlus imp, int s, String chosenAlg){
	//calculate thresholds	
	imp.setSlice(s);
	imp.getProcessor().setSliceNumber(s);
	imp.getProcessor().setAutoThreshold(Method.valueOf(Method.class, chosenAlg), true);
	//Before: IJ.setAutoThreshold(imp, (chosenAlg + " dark"));
	imp.getProcessor().setSliceNumber(s);
	return imp.getProcessor().getMinThreshold();
}

/**
 * @return a new ImagePlus exlusively containing the selected <timepoint> of the ImagePlus <imp>
 * Range of <timepoint>: 1 <= timepoint <= imp.getNFrames()
 * */
private ImagePlus getSelectedTimepoints (ImagePlus imp, int firstTimepoint, int lastTimepoint, boolean copyOverlay){
	ImagePlus outImp = IJ.createHyperStack("Selected Timepoints", imp.getWidth(), imp.getHeight(),
			imp.getNChannels(), imp.getNSlices(), lastTimepoint-firstTimepoint+1, imp.getBitDepth());
//	outImp.setOpenAsHyperStack(true);
	int zO, zN;
	for(int t = firstTimepoint; t <= lastTimepoint; t++){
		if(keepAwake) {
			stayAwake();
		}
		for(int x = 0; x < imp.getWidth(); x++){
			for(int y = 0; y < imp.getHeight(); y++){
				for(int s = 0; s < imp.getNSlices(); s++){
					zO = imp.getStackIndex(1, (s+1), t) - 1;
					zN = outImp.getStackIndex(1, s+1, t - firstTimepoint + 1) - 1;
					outImp.getStack().setVoxel(x, y, zN, imp.getStack().getVoxel(x, y, zO));	
				}
			}
		}
	}	
	outImp.setOpenAsHyperStack(true);
	outImp.setCalibration(imp.getCalibration());
	if(copyOverlay) outImp.setOverlay(imp.getOverlay().duplicate());	
	return outImp;
}

/**
 * Calculates a threshold in the histogram of ImagePlus <parImp> for the image <imp>
 * Only the slice images between the indicated <startSliceImage> and <endSliceImage> are included into calculation
 * */
private double getHistogramThreshold (ImagePlus imp, String algorithm){
	//calculate thresholds	
	IJ.setAutoThreshold(imp, (algorithm +" dark stack"));
	//TODO Find a way to replace with imp.getProcessor().setAutoThreshold(Method.valueOf(Method.class,algorithm), true);
	return imp.getProcessor().getMinThreshold();
}

private void segmentImage(ImagePlus imp, double threshold, int z, ImagePlus impTemp, int zTemp){
	double maxValue = Math.pow(2.0, imp.getBitDepth()) - 1;		
	for(int x = 0; x < imp.getWidth(); x++){
		for(int y = 0; y < imp.getHeight(); y++){
			double pxintensity = impTemp.getStack().getVoxel(x,y,zTemp);
			if(pxintensity < threshold){
				imp.getStack().setVoxel( x, y, z, 0.0);
			}else{
				countSegmentedVoxel(imp, x, y, z);		// zero-intensity loss warning
				if(keepIntensities == false){
					imp.getStack().setVoxel( x, y, z, maxValue);
				}
			}
		}
	}
}

/**
 *	Count one voxel that passed the threshold, and whether it will be written as 0 anyway.
 *
 *	Only meaningful with "keep intensities": the binary styles write the channel maximum, so no mask
 *	voxel is lost and the count would always be 0. Reported by reportMaskMetrics.
 */
private void countSegmentedVoxel(ImagePlus imp, int x, int y, int z){
	if(currentChannelIndex < 0 || maskVoxelCount == null
			|| currentChannelIndex >= maskVoxelCount.length)	return;
	maskVoxelCount [currentChannelIndex] ++;
	if(keepIntensities && imp.getStack().getVoxel(x, y, z) == 0.0){
		zeroIntensityVoxelCount [currentChannelIndex] ++;
	}
}

/**
 * Stretch a segmented channel's display range so its content is visible: label images (values 1..N)
 * and binary/thresholded masks otherwise sit near-black against the full bit-depth range. Display only;
 * pixel values are untouched.
 */
private void autoRangeSegmentedChannel(ImagePlus imp, int channel){
	int idx = imp.getStackIndex(channel, 1, 1);
	double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
	for(int s = 0; s < imp.getNSlices(); s++){
		for(int t = 0; t < imp.getNFrames(); t++){
			ij.process.ImageProcessor ip =
				imp.getStack().getProcessor(imp.getStackIndex(channel, s+1, t+1));
			for(int i = 0; i < ip.getPixelCount(); i++){
				float v = ip.getf(i);
				if(v < min) min = v;
				if(v > max) max = v;
			}
		}
	}
	if(max < min) return;			// empty
	if(max == min) max = min + 1;	// avoid a zero-width range
	imp.setC(channel);
	imp.setDisplayRange(min, max);
}

private boolean requestHysteresisPrefs(String task, int c) {
	GenericDialog gd = new GenericDialog(PLUGIN_NAME + " - Hysteresis thresholding");
	gd.addMessage(PLUGIN_NAME + " - Version " + PLUGIN_VERSION + "",
			SUPER_HEADING_FONT);
	gd.setInsets(10,0,0);	gd.addMessage("Insert processing settings for " + task, new Font("Sansserif", Font.PLAIN, 16));
	gd.setInsets(0,0,0);	gd.addMessage("Hysteresis thresholding requires the '3D ImageJ suite' (https://imagej.net/plugins/3d-imagej-suite/).", new Font("Sansserif", 2, 12));
	gd.setInsets(0,0,0);	gd.addMessage("If you have not installed it yet, please install the '3D ImageJ suite' as explained in the CiliaQ Wiki:", new Font("Sansserif", 2, 12));
	gd.setInsets(0,0,0);	gd.addMessage("https://github.com/hansenjn/CiliaQ/wiki/Installing-CiliaQ", new Font("Sansserif", 2, 12));
	
	gd.setInsets(20,0,0);	gd.addChoice("Select method for low threshold", hystAlgorithms, chosenLowAlg [c]);
	gd.setInsets(0,0,0);	gd.addNumericField("Low threshold (if 'CUSTOM threshold' is chosen)", customLowThr [c], 5);
	gd.setInsets(0,0,0);	gd.addChoice("Select method for high threshold", hystAlgorithms, chosenHighAlg [c]);
	gd.setInsets(0,0,0);	gd.addNumericField("High threshold (if 'CUSTOM threshold' is chosen)", customHighThr [c], 5);

	// show Dialog-----------------------------------------------------------------
	gd.showDialog();

	// read and process variables--------------------------------------------------
	chosenLowAlg [c] = gd.getNextChoice();
	customLowThr [c] = gd.getNextNumber();
	chosenHighAlg [c] = gd.getNextChoice();
	customHighThr [c] = gd.getNextNumber();
	if (gd.wasCanceled()) {
		return false;
	}
	return true;
}

private void addSettingsBlockToPanel(TextCollector tp, Date startDate, String name, boolean multiSeries, int series, ImagePlus imp){
	tp.append("Starting date:	" + FullDateFormatter.format(startDate));
	if(multiSeries) {
		tp.append("Image name:	" + name + "	series:	" + (series+1));
	}else{
		tp.append("Image name:	" + name);		
	}
	tp.append("Preparation settings:	");
	tp.append("	Segmentation style:	" + chosenImageStyle);
	
	for(int i = 0; i < channelIDs.length; i++){
		tp.append("	Segmented channel " + (i+1));
		tp.append("		Channel Nr:	" + df0.format(channelIDs [i]));
		if(includeDuplicateChannel [i]){
			tp.append("		Channel duplicated to include a copy of the channel that is not segmented.");
		}else{tp.append("");}
		if(medianFilter [i]){
			tp.append("		Median filter before blurring:	" + df3.format(medianRadius[i]));
		}else{tp.append("");}
		if(preBlur [i]){
			tp.append("		Gaussian blur before background removal:	" + df3.format(preBlurRadius[i]));
		}else{tp.append("");}
		if(subtractBackground [i]){
			tp.append("		Subtract Background:	" + df3.format(subtractBGRadius[i]));
		}else{tp.append("");}
		if(divideBackground [i]){
			tp.append("		Divide By Background:	" + df3.format(divideBGRadius[i]));
		}else{tp.append("");}
		if(additionalBlur [i]){
			tp.append("		Additional blur with Gaussian:	" + df3.format(additionalBlurRadius[i]));
		}else{tp.append("");}
		if("CANNY 3D".equals(chosenAlgorithms [i])){
			tp.append("		Segmentation method:	" + "Canny 3D Thresholder, a plugin by Sebastian Rassmann,"
					+ " see https://github.com/sRassmann/canny3d-thresholder for a descriptions.");
			tp.append("			Gauss sigma:	" + cannySettings[i].getGaussSigma());
			tp.append("			Canny alpha:	" + cannySettings[i].getCannyAlpha());
			tp.append("			Low threshold method (hysteresis thresholding):	" + cannySettings[i].getLowThresholdAlgorithm());
			if(cannySettings[i].customValueForLowThreshold()) {
				tp.append("			Manually selected low threshold:	" + cannySettings[i].getLowThreshold());
			}
			tp.append("			High threshold method (hysteresis thresholding):	" + cannySettings[i].getHighThresholdAlgorithm());
			if(cannySettings[i].customValueForHighThreshold()) {
				tp.append("			Manually selected high threshold:	" + cannySettings[i].getHighThreshold());
			}
		}else if ("HYSTERESIS threshold".equals(chosenAlgorithms [i])){
			tp.append("		Segmentation method:	" + "HYSTERESIS threshold");
			tp.append("			Low threshold method (hysteresis thresholding):	" + chosenLowAlg [i]);
			if(chosenLowAlg [i].equals(hystAlgorithms [0])) {
				tp.append("			Manually selected low threshold:	" + customLowThr [i]);
			}
			tp.append("			High threshold method (hysteresis thresholding):	" + chosenHighAlg [i]);
			if(chosenHighAlg [i].equals(hystAlgorithms [0])) {
				tp.append("			Manually selected high threshold:	" + customHighThr [i]);
			}
			if(chosenHighAlg [i].equals(hystAlgorithms[0]) && chosenLowAlg [i].equals(hystAlgorithms[0])){
			}else {
				tp.append("		Stack processing:	" + chosenStackMethods [i]);
			}
		}else if ("CUSTOM threshold".equals(chosenAlgorithms[i])){
			tp.append("		Segmentation method:	" + "CUSTOM threshold");
			tp.append("			Custom threshold value:	" + df6.format(customThr [i]));
		}else if (RIDGE_DETECTION.equals(chosenAlgorithms[i])){
			tp.append("		Segmentation method:	" + "RIDGE DETECTION (filaments), Steger line detector"
					+ " via the Ridge Detection plugin by Thorsten Wagner and Mark Hiner,"
					+ " see https://imagej.net/plugins/ridge-detection for a description.");
			tp.append(indentReport(ridgeSettings [i].report()));
			if(separateTimesteps[i]) {
				tp.append("		Every time step separately segmented.");
			}else {
				tp.append("");
			}
		}else if (ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.NAME.equals(chosenAlgorithms[i])){
			tp.append("		Segmentation method:	" + ciliaQ_Prep_jnh.local_threshold.PhansalkarSettings.NAME
					+ " - a local threshold that copes with objects differing in brightness across the"
					+ " image. Computed in-house on the original bit depth.");
			if(phansalkarSettings [i] != null)	tp.append(indentReport(phansalkarSettings [i].report()));
			/*	No Stack processing line: a local threshold surface is 2D by nature, so it is always
			 *	per slice and the stack-processing choice does not apply. Printing one would imply a
			 *	setting had an effect that it does not. */
			tp.append("		Stack processing:	not applicable (a local threshold is computed per slice)");
			if(separateTimesteps[i]) {
				tp.append("		Every time step separately segmented.");
			}else {
				tp.append("");
			}
		}else if (ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.isFractionMethod(
				chosenAlgorithms[i])){
			boolean loc = ciliaQ_Prep_jnh.fraction_threshold.FractionSettings.LOCAL.equals(
					chosenAlgorithms[i]);
			tp.append("		Segmentation method:	" + chosenAlgorithms [i] + " - the threshold sits at a"
					+ " fraction of the way from the background level up to the signal level"
					+ (loc ? ", computed per tile and floored at the global value." : "."));
			if(fractionSettings [i] != null)	tp.append(indentReport(fractionSettings [i].report(loc)));
			tp.append("		Stack processing:	" + chosenStackMethods [i]);
			if(separateTimesteps[i]) {
				tp.append("		Every time step separately segmented.");
			}else {
				tp.append("");
			}
		}else if (EXPANSION_SEEDS.equals(chosenAlgorithms[i])){
			tp.append("		Segmentation method:	" + "EXPANSION from seeds - competitive growth from"
					+ " seeds into the thresholded object extent, producing a label image."
					+ " Separates touching objects; crossing objects are left whole on purpose.");
			tp.append(indentReport(expansionSettings [i].report()));
			tp.append("		Stack processing:	" + chosenStackMethods [i]);
			if(separateTimesteps[i]) {
				tp.append("		Every time step separately segmented.");
			}else {
				tp.append("");
			}
		}else if (SPOT_DETECTION.equals(chosenAlgorithms[i])){
			tp.append("		Segmentation method:	" + "SPOT DETECTION (multi-scale Difference-of-Gaussians"
					+ " followed by maximum detection).");
			tp.append(indentReport(spotSettings [i].report()));
			if(separateTimesteps[i]) {
				tp.append("		Every time step separately segmented.");
			}else {
				tp.append("");
			}
		}else{
			tp.append("		Segmentation method:	applying intensity threshold based on the " + chosenAlgorithms [i] + " threshold algorithm.");
			tp.append("		Stack processing:	" + chosenStackMethods [i]);
			if(separateTimesteps[i]) {
				tp.append("		Every time step separately segmented.");
			}else {
				tp.append("");				
			}
		}	
	}
	tp.append("");
}

/**	Read a settings sub-block as key/value pairs instead of a fixed line sequence.
*
*	In prior versions, reading Canny and Hysteresis settings consumed a fixed number of lines with br.readLine()
*	and abort with IJ.error if any is not the expected one. With new methods from V0.2.1 on, having blocks that
*	vary in length: RidgeSettings.report() emits either one "derived from the image" line or five lines with
*	the Steger values, depending on whether the values existed when it was written. A key/value read is
*	also insensitive to future reordering or added fields.
*
*	Uses mark/reset so the line that ends the block is put back for the outer loop to handle - without
*	that, the sub-parser would swallow the next channel's header.
*
*	@param br  positioned just after the "Segmentation method:" line
*	@return    text before the last tab (trimmed) -> text after it, for every indented line of the block
*/
private java.util.HashMap<String,String> readIndentedBlock(BufferedReader br) throws java.io.IOException {
	java.util.HashMap<String,String> kv = new java.util.HashMap<String,String>();
	while(true){
		br.mark(8192);
		String l = br.readLine();
		if(l == null)	break;
		if(l.trim().length() == 0)	continue;			// report() pads with blank lines
		/*
		 * Entries sit at three tabs. The block's own heading ("Ridge detection settings:") sits at two,
		 * so it has to be skipped explicitly - stopping at it was the first version of this method and
		 * it returned an EMPTY map, which silently restored every default instead of the saved values.
		 * Only a two-tab line ending in "settings:" may be skipped: the other two-tab line the writer
		 * can emit here is "Every time step separately segmented.", and that one belongs to the outer
		 * loop. Swallowing it would silently drop separateTimesteps.
		 */
		if(l.startsWith("			")){
			// three tabs or deeper: an entry of this block
		}else if(l.startsWith("		") && l.trim().endsWith("settings:")){
			continue;									// the block heading
		}else{
			br.reset();									// not ours - give it back
			break;
		}
		int t = l.lastIndexOf("	");
		if(t < 0)	continue;
		kv.put(l.substring(0, t).trim(), l.substring(t + 1).trim());
	}
	return kv;
}

/**	Match a saved threshold-algorithm name against the known list by EXACT equality.
*	Exact, not contains: "IsoData" is a substring of "IJ_IsoData", and that confusion was already
*	fixed once in V0.2.0 for the Canny reader. Same trap, same fix. */
private String matchThrAlgorithm(String saved, String fallback){
	saved = saved.trim();
	for(int a = 0; a < ciliaQ_Prep_jnh.expansion.ExpansionSettings.lowThrAlgorithms.length; a++){
		if(saved.equals(ciliaQ_Prep_jnh.expansion.ExpansionSettings.lowThrAlgorithms [a]))
			return ciliaQ_Prep_jnh.expansion.ExpansionSettings.lowThrAlgorithms [a];
	}
	IJ.log(PLUGIN_NAME + ": unknown threshold method '" + saved + "', keeping " + fallback);
	return fallback;
}

/**	@return the raw value whose key starts with keyPrefix, or null. Prefix matching keeps callers free of
*	the unit suffixes in the keys, e.g. "Expected filament width (px)". Use this rather than
*	HashMap.get: an exact get() silently misses every key that carries a unit. */
private String blockValue(java.util.HashMap<String,String> kv, String keyPrefix){
	for(java.util.Map.Entry<String,String> e : kv.entrySet()){
		if(e.getKey().startsWith(keyPrefix))	return e.getValue();
	}
	return null;
}

private double blockDouble(java.util.HashMap<String,String> kv, String keyPrefix, double fallback){
	String v = blockValue(kv, keyPrefix);
	return v == null ? fallback : parseNum(v, fallback);
}

private boolean blockBoolean(java.util.HashMap<String,String> kv, String keyPrefix, boolean fallback){
	String v = blockValue(kv, keyPrefix);
	return v == null ? fallback : Boolean.parseBoolean(v.trim());
}

/**	New V0.1.0: Decimal-comma tolerant parse, matching what the rest of the reader does by hand at every
 * 	call site. A malformed value returns the fallback rather than aborting the whole load. */
private double parseNum(String s, double fallback){
	if(s == null)	return fallback;
	s = s.trim();
	if(s.contains(",") && !s.contains("."))	s = s.replace(",", ".");
	try{
		return Double.parseDouble(s);
	}catch(NumberFormatException e){
		IJ.log(PLUGIN_NAME + ": could not read the number '" + s + "', keeping " + fallback);
		return fallback;
	}
}

/**	Fit a settings-object report() block into the settings section's indentation.
*	report() emits its heading at one tab and its entries at two; the settings section puts headings at
*	two tabs ("Segmentation method:") and entries at three, so one tab is added to every non-blank line.
*	Trailing blank lines are dropped, because TextPanel turns each into a visible empty row. */
private String indentReport(String report){
	if(report == null)	return "";
	String [] lines = report.split("\n", -1);
	StringBuilder sb = new StringBuilder();
	int last = lines.length - 1;
	while(last >= 0 && lines [last].trim().length() == 0)	last--;
	for(int i = 0; i <= last; i++){
		if(i > 0)	sb.append("\n");
		if(lines [i].trim().length() > 0)	sb.append("	");
		sb.append(lines [i]);
	}
	return sb.toString();
}

/** Write text straight to disk, bypassing ImageJ I/O (and the macro recorder). 
 * Added in version V0.2.0. */
private void writeTextFile(String path, String text){
	try{
		java.io.FileWriter fw = new java.io.FileWriter(path);
		java.io.BufferedWriter bw = new java.io.BufferedWriter(fw);
		if(text != null) bw.write(text);
		bw.close();
		fw.close();
	}catch(java.io.IOException e){
		progress.notifyMessage("Could not write metadata file '" + path + "': " + e.getMessage(),
			ProgressReporter.ERROR);
	}
}

/** @return a newline-joined stack trace, as the error messages below all build by hand. */
private static String stackTraceToString(Throwable e){
	StringBuilder sb = new StringBuilder();
	for(StackTraceElement line : e.getStackTrace()){
		sb.append(" \n ").append(line.toString());
	}
	return sb.toString();
}

private void stayAwake() {
	if(robo == null) return;
	robo.mouseMove(MouseInfo.getPointerInfo().getLocation().x, MouseInfo.getPointerInfo().getLocation().y);
}
}
