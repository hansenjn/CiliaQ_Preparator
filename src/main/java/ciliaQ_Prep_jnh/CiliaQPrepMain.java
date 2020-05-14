package ciliaQ_Prep_jnh;
/** ===============================================================================
* CiliaQ_Preparator Version 0.0.2
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
* Date: September 29, 2019 (This Version: May 14, 2020)
*   
* For any questions please feel free to contact me (jan.hansen@uni-bonn.de).
* =============================================================================== */

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.text.*;
import javax.swing.UIManager;

import ij.*;
import ij.gui.*;
import ij.io.*;
import ij.measure.*;
import ij.plugin.*;
import ij.process.LUT;
import ij.text.*;
import ij.process.AutoThresholder.Method;

public class CiliaQPrepMain implements PlugIn, Measurements {
	//Name variables
	static final String PLUGINNAME = "CiliaQ Preparator";
	static final String PLUGINVERSION = "0.0.2";
	
	//Fix fonts
	static final Font SuperHeadingFont = new Font("Sansserif", Font.BOLD, 16);
	static final Font HeadingFont = new Font("Sansserif", Font.BOLD, 14);
	static final Font SubHeadingFont = new Font("Sansserif", Font.BOLD, 12);
	static final Font TextFont = new Font("Sansserif", Font.PLAIN, 12);
	static final Font InstructionsFont = new Font("Sansserif", 2, 12);
	static final Font RoiFont = new Font("Sansserif", Font.PLAIN, 12);
	
	DecimalFormat df6 = new DecimalFormat("#0.000000");
	DecimalFormat df3 = new DecimalFormat("#0.000");
	DecimalFormat df0 = new DecimalFormat("#0");
	
	static SimpleDateFormat NameDateFormatter = new SimpleDateFormat("yyMMdd_HHmmss");
	static SimpleDateFormat FullDateFormatter = new SimpleDateFormat("yyyy-MM-dd	HH:mm:ss");
	static SimpleDateFormat FullDateFormatter2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	
	//Progress Dialog
	ProgressDialog progress;	
	boolean processingDone = false;	
	boolean continueProcessing = true;
	
	//-----------------define params for Dialog-----------------
	static final String[] taskVariant = {"active image in FIJI","multiple images (open multi-task manager)", "all images open in FIJI"};
	String selectedTaskVariant = taskVariant[1];
	int tasks = 1;
	
	
	boolean [] segmentChannels = new boolean [] {true, false, false};
	
	int [] channelIDs = new int [] {1,2,3};
	
	final static String[] stackMethod = {"apply threshold determined in the stack histogram",
			"apply threshold determined in a maximum-intensity-projection",
			"input image is no stack"};	
	
	String chosenStackMethods [] = new String [] {stackMethod[1],stackMethod[1],stackMethod[1]};
	boolean subtractBackground [] = new boolean [] {false, false, false};
	boolean includeDuplicateChannel [] = new boolean [] {true,true,true};
	double subtractBGRadius [] = new double [] {10.0, 10.0, 10.0};
	boolean separateTimesteps [] = new boolean [] {false, false, false};	
	String [] algorithm = {"Default", "IJ_IsoData", "Huang", "Intermodes", "IsoData", "Li", "MaxEntropy", "Mean", "MinError", "Minimum", "Moments", "Otsu", "Percentile", "RenyiEntropy", "Shanbhag", "Triangle", "Yen"};
	String chosenAlgorithms [] = new String [] {"RenyiEntropy","RenyiEntropy","RenyiEntropy"};
	static final String[] outputVariant = {"save as filename + suffix 'CQP'", "save as filename + suffix 'CQP' + date"};
	String chosenOutputName = outputVariant[0];
	
	static final String[] intensityVariant = {"Keep intensities above threshold (creates a background-removed image)",
			"Set intensities above thresholds to maximum possible intensity value (creates a binary image)"};
	boolean keepIntensities = false;
	String chosenImageStyle = intensityVariant[0];
	
	static final String[] nrFormats = {"US (0.00...)", "Germany (0,00...)"};
	String ChosenNumberFormat = nrFormats[0];
	
		
	//-----------------define params for Dialog-----------------
	
	//Variables for processing of an individual task
//		enum channelType {PLAQUE,CELL,NEURITE};
	
public void run(String arg) {
//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
//-------------------------GenericDialog--------------------------------------
//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
	
	GenericDialog gd = new GenericDialog(PLUGINNAME + " - set parameters");	
	//show Dialog-----------------------------------------------------------------
	//.setInsets(top, left, bottom)
	gd.setInsets(0,0,0);	gd.addMessage(PLUGINNAME + ", Version " + PLUGINVERSION + ", \u00a9 2019-2020 JN Hansen", SuperHeadingFont);
	gd.setInsets(5,0,0);	gd.addChoice("process ", taskVariant, selectedTaskVariant);
	gd.setInsets(0,0,0);	gd.addMessage("The plugin processes .tif images or calls a BioFormats plugin to open different formats.", InstructionsFont);
	gd.setInsets(0,0,0);	gd.addMessage("The BioFormats plugin is installed in FIJI or can be manually installed into imagej.", InstructionsFont);
	
	gd.setInsets(10,0,0);	gd.addMessage("Channel to be segmented (i.e. reconstruction channel for CiliaQ)", HeadingFont);	
	gd.setInsets(0,10,0);	gd.addNumericField("Channel Nr (>= 1 & <= nr of channels)", channelIDs[0], 0);
	gd.setInsets(0,10,0);	gd.addCheckbox("Include also an unsegmented copy of the channel", includeDuplicateChannel [0]);
	gd.setInsets(0,10,0);	gd.addCheckbox("Subtract Background before segmentation - radius", subtractBackground [0]);
	gd.setInsets(-23,100,0);	gd.addNumericField("", subtractBGRadius[0], 2);
	gd.setInsets(0,10,0);	gd.addChoice("Threshold algorithm", algorithm, chosenAlgorithms[0]);
	gd.setInsets(0,10,0);	gd.addChoice("Stack handling: ", stackMethod, chosenStackMethods[0]);	
	gd.setInsets(0,10,0);	gd.addCheckbox("Threshold every time step independently ", separateTimesteps [0]);		
	
	gd.setInsets(10,0,0);	gd.addMessage("OPTIONAL: More channels to be segmented", HeadingFont);	
	gd.setInsets(0,0,0);	gd.addCheckbox("Segment a second channel", segmentChannels [1]);
	gd.setInsets(0,10,0);	gd.addNumericField("Channel Nr (>= 1 & <= nr of channels)", channelIDs[1], 0);
	gd.setInsets(0,10,0);	gd.addCheckbox("Include also an unsegmented copy of the channel", includeDuplicateChannel [1]);
	gd.setInsets(0,10,0);	gd.addCheckbox("Subtract Background before segmentation - radius", subtractBackground [1]);
	gd.setInsets(-23,100,0);	gd.addNumericField("", subtractBGRadius[1], 2);
	gd.setInsets(0,10,0);	gd.addChoice("Threshold algorithm", algorithm, chosenAlgorithms[1]);
	gd.setInsets(0,10,0);	gd.addChoice("Stack handling: ", stackMethod, chosenStackMethods[1]);
	gd.setInsets(0,10,0);	gd.addCheckbox("Threshold every time step independently ", separateTimesteps [1]);	
	
	gd.setInsets(10,0,0);	gd.addCheckbox("Segment a third channel", segmentChannels [2]);	
	gd.setInsets(0,10,0);	gd.addNumericField("Channel Nr (>= 1 & <= nr of channels)", channelIDs[2], 0);
	gd.setInsets(0,10,0);	gd.addCheckbox("Include also an unsegmented copy of the channel", includeDuplicateChannel [2]);
	gd.setInsets(0,10,0);	gd.addCheckbox("Subtract Background before segmentation - radius", subtractBackground [2]);
	gd.setInsets(-23,100,0);	gd.addNumericField("", subtractBGRadius[2], 2);
	gd.setInsets(0,10,0);	gd.addChoice("Threshold algorithm", algorithm, chosenAlgorithms[2]);
	gd.setInsets(0,10,0);	gd.addChoice("Stack handling: ", stackMethod, chosenStackMethods[2]);	
	gd.setInsets(0,10,0);	gd.addCheckbox("Threshold every time step independently ", separateTimesteps [2]);
		
	gd.setInsets(10,0,0);	gd.addMessage("GENERAL SETTINGS:", HeadingFont);	
	gd.setInsets(5,0,0);	gd.addChoice("Segmentation style: ", intensityVariant, intensityVariant [0]);
	gd.setInsets(5,0,0);	gd.addChoice("Output image name: ", outputVariant, chosenOutputName);
	gd.setInsets(5,0,0);	gd.addChoice("output number format", nrFormats, nrFormats[0]);
	
	gd.showDialog();
	//show Dialog-----------------------------------------------------------------

	//read and process variables--------------------------------------------------	
	selectedTaskVariant = gd.getNextChoice();
	
	channelIDs [0] = (int) gd.getNextNumber();
	includeDuplicateChannel [0] = gd.getNextBoolean();
	subtractBackground [0] = gd.getNextBoolean();
	subtractBGRadius [0] = (double) gd.getNextNumber();
	chosenAlgorithms [0] = gd.getNextChoice();
	chosenStackMethods [0] = gd.getNextChoice();
	separateTimesteps [0] = gd.getNextBoolean();
	
	segmentChannels [1] = gd.getNextBoolean();
	channelIDs [1] = (int) gd.getNextNumber();
	includeDuplicateChannel [1] = gd.getNextBoolean();
	subtractBackground [1] = gd.getNextBoolean();
	subtractBGRadius [1] = (double) gd.getNextNumber();
	chosenAlgorithms [1] = gd.getNextChoice();
	chosenStackMethods [1] = gd.getNextChoice();
	separateTimesteps [1] = gd.getNextBoolean();
	
	segmentChannels [2] = gd.getNextBoolean();
	channelIDs [2] = (int) gd.getNextNumber();
	includeDuplicateChannel [2] = gd.getNextBoolean();
	subtractBackground [2] = gd.getNextBoolean();
	subtractBGRadius [2] = (double) gd.getNextNumber();
	chosenAlgorithms [2] = gd.getNextChoice();
	chosenStackMethods [2] = gd.getNextChoice();
	separateTimesteps [2] = gd.getNextBoolean();
	
	chosenImageStyle = gd.getNextChoice();
	if(chosenImageStyle.equals(intensityVariant [0])){
		keepIntensities = true;
	}
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
	
	//read and process variables--------------------------------------------------
	if (gd.wasCanceled()) return;
	
//&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&
//---------------------end-GenericDialog-end----------------------------------
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
					Thread.currentThread().sleep(50);
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
			if(WindowManager.getIDList()==null){
				new WaitForUserDialog("Plugin canceled - no image open in FIJI!").show();
				return;
			}
			FileInfo info = WindowManager.getCurrentImage().getOriginalFileInfo();
			name [0] = info.fileName;	//get name
			dir [0] = info.directory;	//get directory
			tasks = 1;
		}else if(selectedTaskVariant.equals(taskVariant[2])){	// all open images
			if(WindowManager.getIDList()==null){
				new WaitForUserDialog("Plugin canceled - no image open in FIJI!").show();
				return;
			}
			int IDlist [] = WindowManager.getIDList();
			tasks = IDlist.length;	
			if(tasks == 1){
				selectedTaskVariant=taskVariant[0];
				FileInfo info = WindowManager.getCurrentImage().getOriginalFileInfo();
				name [0] = info.fileName;	//get name
				dir [0] = info.directory;	//get directory
			}else{
				name = new String [tasks];
				dir = new String [tasks];
				allImps = new ImagePlus [tasks];
				for(int i = 0; i < tasks; i++){
					allImps[i] = WindowManager.getImage(IDlist[i]); 
					FileInfo info = allImps[i].getOriginalFileInfo();
					name [i] = info.fileName;	//get name
					dir [i] = info.directory;	//get directory
				}		
			}
					
		}
	}
	 	
	//add progressDialog
	progress = new ProgressDialog(name, tasks);
	progress.setLocation(0,0);
	progress.setVisible(true);
	progress.addWindowListener(new java.awt.event.WindowAdapter() {
        public void windowClosing(WindowEvent winEvt) {
        	if(processingDone==false){
        		IJ.error("Script stopped...");
        	}
        	continueProcessing = false;	        	
        	return;
        }
	});

	
   	ImagePlus imp; 	  	
	for(int task = 0; task < tasks; task++){
		running: while(continueProcessing){
			Date startDate = new Date();
			progress.updateBarText("in progress...");
			//Check for problems
			if(name[task].substring(name[task].lastIndexOf("."),name[task].length()).equals(".txt")){
				progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": A file is no image! Could not be processed!", ProgressDialog.ERROR);
				progress.moveTask(task);	
				break running;
			}
			if(name[task].substring(name[task].lastIndexOf("."),name[task].length()).equals(".zip")){	
				progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": A file is no image! Could not be processed!", ProgressDialog.ERROR);
				progress.moveTask(task);	
				break running;
			}
			//Check for problems

			//open Image
		   	try{
		   		if(selectedTaskVariant.equals(taskVariant[1])){
		   			if(name[task].contains(".tif")){
		   				//TIFF file
		   				imp = IJ.openImage(""+dir[task]+name[task]+"");		
		   			}else{
		   				//bio format reader
		   				IJ.run("Bio-Formats", "open=[" +dir[task] + name[task]
		   						+ "] autoscale color_mode=Default rois_import=[ROI manager] view=Hyperstack stack_order=XYCZT");
		   				imp = WindowManager.getCurrentImage();	
		   				imp.setDisplayMode(IJ.COMPOSITE);
		   			}
		   			imp.hide();
					imp.deleteRoi();
//		   			imp = IJ.openImage(""+dir[task]+name[task]+"");			   			
//					imp.deleteRoi();
		   		}else if(selectedTaskVariant.equals(taskVariant[0])){
		   			imp = WindowManager.getCurrentImage();
		   			imp.deleteRoi();
		   		}else{
		   			imp = allImps[task];
		   			imp.deleteRoi();
		   		}
		   	}catch (Exception e) {
		   		progress.notifyMessage("Task " + (task+1) + "/" + tasks + ": file is no image - could not be processed!", ProgressDialog.ERROR);
				progress.moveTask(task);	
				break running;
			}
		   	//open Image
		   	
		   	
		   	//Create Outputfilename
		   	progress.updateBarText("Create output filename");				
			String filePrefix;
			if(name[task].contains(".")){
				filePrefix = name[task].substring(0,name[task].lastIndexOf(".")) + "_CQP";
			}else{
				filePrefix = name[task] + "_CQP";
			}
			
			if(chosenOutputName.equals(outputVariant[1])){
				//saveDate
				filePrefix += "_" + NameDateFormatter.format(startDate);
			}
			
			filePrefix = dir[task] + filePrefix;
		   	
			
		/******************************************************************
		*** 						Processing							***	
		*******************************************************************/
		   	ImagePlus tempImp, procImp;
		   	double thresholds [], threshold;
		   	procImp = imp.duplicate();
		   	procImp.setCalibration(imp.getCalibration());
		   	procImp.deleteRoi();
		   	procImp.hide();
		   	
			//start logging metadata
			TextPanel tp1 = new TextPanel("results");
			addSettingsBlockToPanel(tp1,  startDate, name[task], imp);
			tp1.append("");
			
			//processing
			int addC = 0;
		   	for(int c = 0; c < segmentChannels.length; c++){
		   		if(segmentChannels [c]){
		   			if(includeDuplicateChannel [c]){
		   				addC ++;
		   			}
		   			
		   			tempImp = copyChannel(procImp, channelIDs[c], false, false);
		   			if(subtractBackground [c]){
		   				progress.updateBarText("Subtract background for channel " + channelIDs [c] + " ...");		
			   			if(tempImp.getStackSize()>1){
				   			IJ.run(tempImp, "Subtract Background...", "rolling=" + subtractBGRadius [c] + " stack");
				   		}else{
				   			IJ.run(tempImp, "Subtract Background...", "rolling=" + subtractBGRadius [c]);
				   		}
			   		}
		   			
		   			progress.updateBarText("Determine threshold for channel " + channelIDs [c] + " ...");				   			
		   			tp1.append("Threshold(s) for channel " + channelIDs [c]);
		   			thresholds = getThresholds(tempImp, chosenStackMethods [c], chosenAlgorithms [c], separateTimesteps [c], progress);
		   			threshold = thresholds [0];
		   			if(!separateTimesteps [c]){
		   				tp1.append("	All timesteps:	" + df3.format(threshold));
		   			}else{
		   				tp1.append("	Time step #	Threshold");
		   			}
		   			for(int t = 0; t < procImp.getNFrames(); t++){
		   				progress.updateBarText("Applying segmentation to channel " + channelIDs [c] + " ...");
		   				progress.addToBar(0.2/procImp.getNFrames());
		   				if(separateTimesteps [c]){
		   					threshold = thresholds [t];	
		   					tp1.append("	" + df0.format(t+1) + "	" + df3.format(threshold));
		   				}
		   				for(int s = 0; s < procImp.getNSlices(); s++){
				   			this.segmentImage(procImp, threshold, procImp.getStackIndex(channelIDs [c], s+1, t+1)-1, 
				   					tempImp, tempImp.getStackIndex(1, s+1, t+1)-1);		   					
		   				}
		   			}
		   			tempImp.changes = false;
		   			tempImp.close();
		   		}
		   	}
		   	
		   	if(addC > 0){
		   		tempImp = IJ.createHyperStack(imp.getTitle() + " cq", imp.getWidth(), imp.getHeight(), 
		   				imp.getNChannels()+addC,
		   				imp.getNSlices(), imp.getNFrames(), imp.getBitDepth());
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
		   								if(c+1 == channelIDs [i] && segmentChannels [i] && includeDuplicateChannel [i]){
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
			   	LUT [] newLuts = new LUT [tempImp.getNChannels()];
			   	
			   	tp1.append("Channels in output image:");
			   	cNew = 0;
			   	String copyStr;
			   	boolean search;
		   		for(int c = 0; c < procImp.getNChannels(); c++){
		   			newLuts [c+cNew] = originalLuts [c];
		   			search = false;
					for(int i = 0; i < channelIDs.length; i++){
						if(c+1 == channelIDs [i] && segmentChannels [i]){
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
				   					if(imp.getStack().getSliceLabel(indexOld+1).equals(null)){
				   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
				   							+  " T" + (f+1) + "/" + procImp.getNFrames();
				   					}else if(imp.getStack().getSliceLabel(indexOld+1).isEmpty()){
				   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
			   							+  " T" + (f+1) + "/" + procImp.getNFrames();
				   					}else{
				   						copyStr = imp.getStack().getSliceLabel(indexOld+1);
				   					}
				   					tempImp.getStack().setSliceLabel("segm " + copyStr, indexNew+1);
			   					}
							}
						
						for(int i = 0; i < channelIDs.length; i++){
							if(c+1 == channelIDs [i] && segmentChannels [i] && includeDuplicateChannel [i]){
								cNew ++;
   								newLuts [c+cNew] = originalLuts [c];
   								tp1.append("Channel " + (c+1+cNew) + ":	" + "previous channel " + (c+1) + "");
   								
   								for(int s = 0; s < procImp.getNSlices(); s++){
   				   					for(int f = 0; f < procImp.getNFrames(); f++){
	   				   					indexOld = imp.getStackIndex(c+1, s+1, f+1)-1;
					   					indexNew = tempImp.getStackIndex(c+cNew+1, s+1, f+1)-1;
					   					if(imp.getStack().getSliceLabel(indexOld+1).equals(null)){
					   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
					   							+  " T" + (f+1) + "/" + procImp.getNFrames();
					   					}else if(imp.getStack().getSliceLabel(indexOld+1).isEmpty()){
					   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
				   							+  " T" + (f+1) + "/" + procImp.getNFrames();
					   					}else{
					   						copyStr = imp.getStack().getSliceLabel(indexOld+1);
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
			   					if(imp.getStack().getSliceLabel(indexOld+1).equals(null)){
			   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
			   							+  " T" + (f+1) + "/" + procImp.getNFrames();
			   					}else if(imp.getStack().getSliceLabel(indexOld+1).isEmpty()){
			   						copyStr = "Channel " + (c+1) + " S" + (s+1) + "/" + procImp.getNSlices() 
		   							+  " T" + (f+1) + "/" + procImp.getNFrames();
			   					}else{
			   						copyStr = imp.getStack().getSliceLabel(indexOld+1);
			   					}
			   					tempImp.getStack().setSliceLabel(copyStr, indexNew+1);
		   					}
						}
					}
				}
		   		
		   		CompositeImage ci = (CompositeImage)tempImp;
				ci.setDisplayMode(IJ.COMPOSITE);
			    ci.setLuts(newLuts);
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
			tp1.saveAs(filePrefix + ".txt");			

			progress.updateBarText("Finished ...");
		   
			
			System.gc();
		/******************************************************************
		*** 							Finish							***	
		*******************************************************************/			
			{
				imp.unlock();	
				if(selectedTaskVariant.equals(taskVariant[1])){
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
	}
}
private void addFooter(TextPanel tp, Date currentDate){
	tp.append("");
	tp.append("Datafile was generated on " + FullDateFormatter2.format(currentDate) + " by '"
			+PLUGINNAME+"', an ImageJ plug-in by Jan Niklas Hansen (jan.hansen@uni-bonn.de, https://github.com/hansenjn/CiliaQ_Preparator).");
	tp.append("The plug-in '"+PLUGINNAME+"' is distributed in the hope that it will be useful,"
			+ " but WITHOUT ANY WARRANTY; without even the implied warranty of"
			+ " MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.");
	tp.append("Plug-in version:	V"+PLUGINVERSION);	
}

private String getOneRowFooter(Date currentDate){
	return  "Datafile was generated on " + FullDateFormatter2.format(currentDate) + " by '"+PLUGINNAME
			+"', an ImageJ plug-in by Jan Niklas Hansen (jan.hansen@uni-bonn.de(jan.hansen@uni-bonn.de, https://github.com/hansenjn/CiliaQ_Preparator))."
			+ "	The plug-in '"+PLUGINNAME+"' is distributed in the hope that it will be useful,"
				+ " but WITHOUT ANY WARRANTY; without even the implied warranty of"
				+ " MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE."
			+"	Plug-in version:	V"+PLUGINVERSION;	
}

/**
 * @param channel: 1 <= channel <= # channels
 * */
private static ImagePlus copyChannel(ImagePlus imp, int channel, boolean adjustDisplayRangeTo16bit, boolean copyOverlay){
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
	if(adjustDisplayRangeTo16bit)	impNew.setDisplayRange(0, 4095);
	if(copyOverlay)	impNew.setOverlay(imp.getOverlay().duplicate());
	impNew.setCalibration(imp.getCalibration());
	return impNew;
}

private double [] getThresholds (ImagePlus imp, String chosenMethod, String chosenAlg, 
		boolean seperateTimeSteps, ProgressDialog progress){
	int startGroup = 1, endGroup = imp.getNFrames();
	ImagePlus selectedImp;
	double thresholds [] = {Double.NaN};
	if(seperateTimeSteps){
		thresholds = new double [endGroup-startGroup+1];
	}
	if(seperateTimeSteps){			
		for(int t = startGroup-1; t < endGroup; t++){
			selectedImp = getSelectedTimepoints(imp, t+1, t+1, false);
			if(chosenMethod.equals(stackMethod[0])){
				thresholds [t-(startGroup-1)] = getHistogramThreshold(selectedImp, chosenAlg);
			}else if(chosenMethod.equals(stackMethod[1])){
				selectedImp = maximumProjection(selectedImp, 1, selectedImp.getStackSize());
				thresholds [t-(startGroup-1)] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);					
			}else{
				thresholds [t-(startGroup-1)] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);		
			}				
			selectedImp.changes = false;
			selectedImp.close();
			System.gc();
			progress.addToBar(0.8/(endGroup-startGroup));
		}
	}else{
		selectedImp = getSelectedTimepoints(imp, startGroup, endGroup, false);
		if(chosenMethod.equals(stackMethod[0])){
			thresholds [0] = getHistogramThreshold(selectedImp, chosenAlg);
		}else if(chosenMethod.equals(stackMethod[1])){
			selectedImp = maximumProjection(selectedImp, 1, selectedImp.getStackSize());
			thresholds [0] = getSingleSliceImageThreshold(selectedImp, 1, chosenAlg);	
		}else{
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
 * @return maximum-intensity-projection image of the specified stack range in the input ImagePlus (imp)
 * startSlice = first slice included into projection (1 < startSlice < NSlices)
 * endSlice = last slice included into projection (1 < endSlice < NSlices)
 * */
private static ImagePlus maximumProjection(ImagePlus imp, int startSlice, int endSlice){
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
private static ImagePlus getSelectedTimepoints (ImagePlus imp, int firstTimepoint, int lastTimepoint, boolean copyOverlay){
	ImagePlus outImp = IJ.createHyperStack("Selected Timepoints", imp.getWidth(), imp.getHeight(),
			imp.getNChannels(), imp.getNSlices(), lastTimepoint-firstTimepoint+1, imp.getBitDepth());
//	outImp.setOpenAsHyperStack(true);
	int zO, zN;
	for(int t = firstTimepoint; t <= lastTimepoint; t++){
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
			}else if(keepIntensities == false){
				imp.getStack().setVoxel( x, y, z, maxValue);
			}
		}
	}		
}

private void addSettingsBlockToPanel(TextPanel tp, Date startDate, String name, ImagePlus imp){
	tp.append("Starting date:	" + FullDateFormatter.format(startDate));
	tp.append("image name:	" + name);
	tp.append("Preparation settings:	");
	tp.append("	Segmentation style:	" + chosenImageStyle);
	tp.append("	Segmented channel 1");
	tp.append("		Channel Nr:	" + df0.format(channelIDs [0]));
	if(includeDuplicateChannel [0]){
		tp.append("		Channel duplicated to include a copy of the channel that is not segmented.");
	}else{tp.append("");}		
	if(subtractBackground [0]){
		tp.append("		Subtract Background:	" + df3.format(subtractBGRadius[0]));
	}else{tp.append("");}
	tp.append("		Algorithm:	" + chosenAlgorithms [0]);
	tp.append("		Stack processing:	" + chosenStackMethods [0]);
	if(segmentChannels [1]){
		tp.append("	Segmented channel 2");
		tp.append("		Channel Nr:	" + df0.format(channelIDs [1]));
		if(includeDuplicateChannel [1]){
			tp.append("		Channel duplicated to include a copy of the channel that is not segmented.");
		}else{tp.append("");}
		if(subtractBackground [1]){
			tp.append("		Subtract Background:	" + df3.format(subtractBGRadius[1]));
		}else{tp.append("");}
		tp.append("		Algorithm:	" + chosenAlgorithms [1]);
		tp.append("		Stack processing:	" + chosenStackMethods [1]);
	}
	if(segmentChannels [2]){
		tp.append("	Segmented channel 3");
		tp.append("		Channel Nr:	" + df0.format(channelIDs [2]));
		if(includeDuplicateChannel [2]){
			tp.append("		Channel duplicated to include a copy of the channel that is not segmented.");
		}else{tp.append("");}
		if(subtractBackground [2]){
			tp.append("		Subtract Background:	" + df3.format(subtractBGRadius[2]));
		}else{tp.append("");}
		tp.append("		Algorithm:	" + chosenAlgorithms [2]);
		tp.append("		Stack processing:	" + chosenStackMethods [2]);
	}
	tp.append("");
}

}//end main class