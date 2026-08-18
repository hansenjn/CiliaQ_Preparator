package ciliaQ_Prep_jnh.canny3d_thresholder;

import ij.IJ;
import ij.ImagePlus;
import ij.macro.Interpreter;

public class Processing {

	/**
	 * Canny 3D Thresholder - Processing class
	 * @author Sebastian Rassmann
	 * 
	 * This code was retrieved from https://github.com/sRassmann/canny3d-thresholder, version v0.1.0; Code was adapted by 
	 * @author Jan N. Hansen
	 * to fit into the CiliaQ_Preparator.
	 * Last modification: Sep 19, 2023.
	 * 
	 * Wraps the logic and real processing of the generated plugin.
	 * 
	 * @param pS ProcessSettings of the task
	 * @param pD Reference to ProgressDialog
	 * @return
	 */

	public static ImagePlus doProcessing(ImagePlus thrChannel, ProcessSettings pS, ciliaQ_Prep_jnh.ProgressReporter pD) {
		// CiliaQ Preparator V0.2.0 update: run the 3D-Suite commands (which normally create and display new images)
		// without visible windows. Interpreter.batchMode is the public field the (non-visible)
		// setBatchMode() toggles internally.
		boolean previousBatch = Interpreter.isBatchMode();
		Interpreter.batchMode = true;
		ImagePlus edges = null, bin = null;
		try{
			pD.updateBarText("Canny 3D: performing edge detection");
			IJ.run(thrChannel, "Gaussian Blur...", "sigma=" + pS.gaussSigma + " stack");
			IJ.run(thrChannel, "3D Edge and Symmetry Filter",
					"alpha=" + pS.cannyAlpha + " radius=10 normalization=10 scaling=2 improved");
			edges = Interpreter.getLastBatchModeImage(); //New in V0.2.0 where we use batchmode: we replaced WindowManager.getCurrentImage().
			thrChannel.hide();
			thrChannel.changes = false;
	//		edges.hide();
	
			pD.updateBarText("Canny 3D: thresholding image");
	
			double lowThr, highThr;
			if (pS.lowThrAlgorithm == "Custom") {
				lowThr = pS.lowThr; // use custom value
			} else {
				IJ.setAutoThreshold(edges, pS.lowThrAlgorithm + " dark stack");
				lowThr = edges.getProcessor().getMinThreshold(); // calculate from stack
			}
			if (pS.highThrAlgorithm == "Custom") {
				highThr = pS.highThr; // use custom value
			} else {
				IJ.setAutoThreshold(edges, pS.highThrAlgorithm + " dark stack");
				highThr = edges.getProcessor().getMinThreshold(); // calculate from stack
			}
	
			IJ.run(edges, "3D Hysteresis Thresholding", "high=" + highThr + " low=" + lowThr);
			bin = Interpreter.getLastBatchModeImage(); //New in V0.2.0 where we use batchmode: we replaced WindowManager.getCurrentImage().
			bin.hide();
			edges.changes = false;
			edges.close();
	
			IJ.run(bin, "8-bit", "");
			
			if(bin.getNSlices()==1) {
				IJ.run(bin, "Fill Holes", "");				
			}else {
				IJ.run(bin, "3D Fill Holes", "");			
			}
	
			thrChannel.changes = false;
			thrChannel.close();
			
			Interpreter.removeBatchModeImage(bin);
			return bin;
		}catch(RuntimeException e){
			// Clean up any partial results so they cannot be flushed to screen on exit.
			if(edges != null){ edges.changes = false; edges.close(); }
			if(bin   != null){ bin.changes   = false; bin.close();   }
			throw e;   // caller (segmentUsingCanny3D) logs and does break running
		}finally {
			// Always restore whatever batch state we found, even on exception.
			Interpreter.batchMode = previousBatch;
		}
	}
}
