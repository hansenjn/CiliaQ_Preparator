# ![CiliaQ](https://github.com/hansenjn/CiliaQ/blob/master/Webfiles/20200618%20CiliaQ%20Logo%20Small.png?raw=true) Preparator
An ImageJ plugin to prepare images for analysis with [CiliaQ](https://github.com/hansenjn/CiliaQ). For all information on how to install, use, and cite CiliaQ_Preparator, please visit the [CiliaQ repository](https://github.com/hansenjn/CiliaQ), which offers a user guide and a wiki for the whole CiliaQ analysis workflow.

Copyright (C) 2019-2026: Jan N. Hansen (contact: jan.hansen (at) uni-bonn.de).

CiliaQ_Preparator has been originally developed in the research group [Biophysical Imaging, Institute of Innate Immunity, Bonn, Germany](https://www.iiibonn.de/dagmar-wachten-lab/dagmar-wachten-lab-science) and is since then maintained by Jan Hansen.

## Download
Download the latest version of the CiliaQ_Preparator plugin file (.jar file) from the [release page](https://github.com/hansenjn/CiliaQ_Preparator/releases).

## Special Notes
CiliaQ_Preparator offers the segmentation method **"CANNY 3D"**. This function was implemented based on code from the ImageJ plugin [canny3d-thresholder](https://github.com/sRassmann/canny3d-thresholder) version [v0.1.0](https://github.com/sRassmann/canny3d-thresholder/releases/tag/0.1.0).

From V0.2.1 on, CiliaQ_Preparator will offer the method **"RIDGE DETECTION"** for segmenting filaments, which uses the Steger line detector. To make this segmentation fully reproducible (identical results on identical input across repeated runs and restarts),
CiliaQ_Preparator includes a modified copy of the Steger line detector from the ImageJ plugin [Ridge Detection](https://github.com/thorstenwagner/ij-ridgedetection) (by Thorsten Wagner and Mark Hiner; original C code by Carsten Steger; detect-lines code by R. Balasubramanian),
located in `.../ciliaQ_Prep_jnh/ij_ridge_detector_steger/`. That code is licensed under the GNU General Public License version 2.0 or (at your option) any later version, and is therefore compatible with and here distributed under the
GNU General Public License version 3.0 of CiliaQ_Preparator. The original copyright and license headers are preserved in each file. The only modifications made ensure deterministic (reproducible) output; a full list of changes, the original source,
and links to the issue/pull request where these fixes were contributed back upstream are documented in the [README of that subpackage](https://github.com/hansenjn/CiliaQ_Preparator/blob/master/src/main/java/ciliaQ_Prep_jnh/ij_ridge_detector_steger/README.md).

Some functions (Hysteresis thresholding, Canny3D) in CiliaQ_Preparator require additional installation of the 3D ImageJ Suite to your FIJI / ImageJ distribution. How to install this 3D ImageJ Suite is explained in the installation instructions for CiliaQ at the CiliaQ wiki: https://github.com/hansenjn/CiliaQ/wiki/Installing-CiliaQ .

