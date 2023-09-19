package ciliaQ_Prep_jnh.canny3d_thresholder;

import java.awt.Font;
import java.util.ArrayList;
import ij.gui.GenericDialog;

/**
 * Canny 3D Thresholder - ProcessSettings class
 * @author Sebastian Rassmann
 * 
 * This code was retrieved from https://github.com/sRassmann/canny3d-thresholder, version v0.1.0,
 * and was adapted by 
 * @author Jan N. Hansen
 * to fit into the CiliaQ_Preparator.
 * Last modification: Sep 18, 2023. 
 */
public class ProcessSettings {	
	static String pluginName = "Canny 3D Thresholder"; 
	static String pluginVersion = "0.1.0";
	
	double gaussSigma = 1.0;
	double cannyAlpha = 5.0;
	public static String[] thrAlgorithms = {"Custom Value", "Huang", "Intermodes", "IsoData", "IJ_IsoData", "Li",
			"MaxEntropy", "Mean", "MinError", "Minimum", "Moments", "Otsu", "Percentile", "RenyiEntropy", "Shanbhag",
			"Triangle", "Yen" };
	String lowThrAlgorithm = "Otsu";
	String highThrAlgorithm = "Triangle";
	double lowThr = 0.0;
	double highThr = 0.0;

	// --------------------- Task data

	ArrayList<String> names = new ArrayList<String>(); // files names with ending (e.g. .tif)
	ArrayList<String> paths = new ArrayList<String>(); // paths to parent dir with last file sep ("/")

	private ProcessSettings() {
		super();
	}

	/**
	 * Constructs new Object and triggers a GD for the user
	 * 
	 * @return User-chosen Processing Settings
	 * @throws Exception
	 */
	public static ProcessSettings initByGD(String Task) throws Exception {
		ProcessSettings inst = new ProcessSettings(); // returned instance of ImageSetting class
		GenericDialog gd = new GenericDialog(pluginName + " - Image Processing Settings");
		gd.addMessage(pluginName + " - Version " + pluginVersion + " (© 2020 Sebastian Rassmann), modified by J.N. Hansen (last mod.: Sep 18, 2023)",
				new Font("Sansserif", Font.BOLD, 14));
		gd.setInsets(10,0,0);	gd.addMessage("Insert processing settings for " + Task, new Font("Sansserif", Font.PLAIN, 16));
		gd.setInsets(0,0,0);	gd.addMessage("Canny 3D Thresholder requires the installation of additional packages in your FIJI/ImageJ distribution.", new Font("Sansserif", 2, 12));
		gd.setInsets(-5,0,0);	gd.addMessage("Please read the paragraph Installation at:", new Font("Sansserif", 2, 12));
		gd.setInsets(-5,20,0);	gd.addMessage("https://github.com/sRassmann/canny3d-thresholder.", new Font("Sansserif", 2, 12));
		
		gd.setInsets(0,0,0);	gd.addNumericField("Sigma for Gaussian blur (pixels)", inst.gaussSigma, 4);
		gd.setInsets(0,0,0);	gd.addNumericField("Alpha (sensitivity for edge detection)", inst.cannyAlpha, 4);
		gd.setInsets(0,0,0);	gd.addChoice("Select method for low threshold", thrAlgorithms, inst.highThrAlgorithm);
		gd.setInsets(0,0,0);	gd.addNumericField("Value (if custom value is chosen)", inst.highThr, 8);
		gd.setInsets(0,0,0);	gd.addChoice("Select method for high threshold", thrAlgorithms, inst.lowThrAlgorithm);
		gd.setInsets(0,0,0);	gd.addNumericField("Value (if custom value is chosen)", inst.lowThr, 8);

		// show Dialog-----------------------------------------------------------------
		gd.showDialog();

		// read and process variables--------------------------------------------------
		inst.gaussSigma = gd.getNextNumber();
		inst.cannyAlpha = gd.getNextNumber();
		inst.highThrAlgorithm = gd.getNextChoice();
		inst.lowThrAlgorithm = gd.getNextChoice();
		inst.lowThr = gd.getNextNumber();
		inst.highThr = gd.getNextNumber();
		if (gd.wasCanceled())
			throw new Exception("GD canceled by user");
		return inst;
	}
	
	public static ProcessSettings initDefault(){
		return new ProcessSettings();
	}
	
	public String toString() {
		StringBuffer sb = new StringBuffer();
		
		sb.append(pluginName + " " + pluginVersion + "\n");
		
		sb.append("\nProcessing settings:");
		sb.append("\nGauss Sigma: 	" + gaussSigma);
		sb.append("\nCanny Alpha:	" + cannyAlpha);
		sb.append("\nLow Threshold Algorithm:	" + lowThrAlgorithm);
		sb.append("\nHigh Threshold Algorithm:	" + highThrAlgorithm);
		sb.append("\nLow Threshold Value (user defined):	" + lowThr);
		sb.append("\nHigh Threshold Value (user defined):	" + highThr);
		
		return sb.toString();
	}
	
	public double getGaussSigma(){
		return gaussSigma;
	}
	public void setGaussSigma(double sigma){
		gaussSigma = sigma;
	}
	
	public double getCannyAlpha(){
		return cannyAlpha;
	}
	public void setCannyAlpha(double alpha){
		cannyAlpha = alpha;
	}
	
	public String getLowThresholdAlgorithm(){
		return lowThrAlgorithm;
	}
	public void setLowThresholdAlgorithm(String alg){
		lowThrAlgorithm = alg;
	}
	
	public String getHighThresholdAlgorithm(){
		return highThrAlgorithm;
	}
	public void setHighThresholdAlgorithm(String alg){
		highThrAlgorithm = alg;
	}
	
	public double getLowThreshold(){
		return lowThr;
	}
	public void setLowThr(double thr){
		lowThr = thr;
	}
	
	public double getHighThreshold(){
		return highThr;
	}
	public void setHighThr(double thr){
		highThr = thr;
	}
	
	public boolean customValueForLowThreshold(){
		if(lowThrAlgorithm.equals(thrAlgorithms[0]))	return true;
		else return false;
	}
	public boolean customValueForHighThreshold(){
		if(highThrAlgorithm.equals(thrAlgorithms[0]))	return true;
		else return false;
	}
	
}
