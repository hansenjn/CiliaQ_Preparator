package ciliaQ_Prep_jnh.canny3d_thresholder;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;

public class Processing {

	/**
	 * Canny 3D Thresholder - Processing class
	 * @author Sebastian Rassmann
	 * 
	 * This code was retrieved from https://github.com/sRassmann/canny3d-thresholder, version v0.1.0; Code was adapted by 
	 * @author Jan N. Hansen
	 * to fit into the CiliaQ_Preparator. 
	 * 
	 * Wraps the logic and real processing of the generated plugin.
	 * 
	 * @param pS ProcessSettings of the task
	 * @param pD Reference to ProgressDialog
	 * @return
	 */

	public static ImagePlus doProcessing(ImagePlus thrChannel, ProcessSettings pS, ciliaQ_Prep_jnh.ProgressDialog pD) {
		pD.updateBarText("Canny 3D: performing edge detection");
		IJ.run(thrChannel, "Gaussian Blur...", "sigma=" + pS.gaussSigma + " stack");
		IJ.run(thrChannel, "3D Edge and Symmetry Filter",
				"alpha=" + pS.cannyAlpha + " radius=10 normalization=10 scaling=2 improved");
		ImagePlus edges = WindowManager.getCurrentImage();	//IJ.getImage();
		thrChannel.hide();
		thrChannel.changes = false;
		edges.hide();

		pD.updateBarText("Canny 3D: thresholding image");

		double lowThr, highThr;
		if (pS.lowThrAlgorithm == "Custom") {
			lowThr = pS.lowThr; // use custom value
		} else {
			IJ.setAutoThreshold(edges, pS.highThrAlgorithm + " dark stack");
			lowThr = edges.getProcessor().getMinThreshold(); // calculate from stack
		}
		if (pS.highThrAlgorithm == "Custom") {
			highThr = pS.highThr; // use custom value
		} else {
			IJ.setAutoThreshold(edges, pS.lowThrAlgorithm + " dark stack");
			highThr = edges.getProcessor().getMinThreshold(); // calculate from stack
		}

		IJ.run(edges, "3D Hysteresis Thresholding", "high=" + highThr + " low=" + lowThr);
		ImagePlus bin = WindowManager.getCurrentImage();	//IJ.getImage();
		bin.hide();
		edges.changes = false;
		edges.close();

		IJ.run(bin, "8-bit", "");
		IJ.run(bin, "3D Fill Holes", "");

		thrChannel.changes = false;
		thrChannel.close();
		return bin;
	}
}
