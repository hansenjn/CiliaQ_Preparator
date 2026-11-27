# ![CiliaQ](https://github.com/hansenjn/CiliaQ/blob/master/Webfiles/20200618%20CiliaQ%20Logo%20Small.png?raw=true) Preparator
An ImageJ plugin to prepare images for analysis with [CiliaQ](https://github.com/hansenjn/CiliaQ). For all information on how to install, use, and cite CiliaQ_Preparator, please visit the [CiliaQ repository](https://github.com/hansenjn/CiliaQ), which offers a user guide and a wiki for the whole CiliaQ analysis workflow.

Copyright (C) 2019-2026: Jan N. Hansen (contact: jan.hansen (at) scilifelab.se).

CiliaQ_Preparator has been originally developed in the research group [Biophysical Imaging, Institute of Innate Immunity, Bonn, Germany](https://www.iiibonn.de/dagmar-wachten-lab/dagmar-wachten-lab-science) and is since then maintained and further extended by Jan Hansen.

## Download
Download the latest version of the CiliaQ_Preparator plugin file (.jar file) from the [release page](https://github.com/hansenjn/CiliaQ_Preparator/releases).

## Special Notes
CiliaQ_Preparator offers the segmentation method **"CANNY 3D"**. This function was implemented based on code from the ImageJ plugin [canny3d-thresholder](https://github.com/sRassmann/canny3d-thresholder) version [v0.1.0](https://github.com/sRassmann/canny3d-thresholder/releases/tag/0.1.0).

From V0.2.1 on, CiliaQ_Preparator offers more segmentation methods:
- **"RIDGE DETECTION"** for filaments such as cilia, using the Steger line detector: one label per filament, so filaments that touch can be told apart.
- **"SPOT DETECTION"** for punctate structures such as basal bodies.
- **"EXPANSION from seeds"** for touching objects (seeds from ridges, spots or bright cores), as a label image.
- The local threshold **"PHANSALKAR"** and the thresholds **"FRACTION of intensity range"** and **"LOCAL FRACTION of intensity range"**.

CiliaQ reads the label images from V0.2.2 on. The settings dialog of each of these methods has a Help button that opens its page in the CiliaQ wiki.

To make ridge detection fully reproducible (identical results on identical input across repeated runs and restarts),
CiliaQ_Preparator includes a modified copy of the Steger line detector from the ImageJ plugin [Ridge Detection](https://github.com/thorstenwagner/ij-ridgedetection) (by Thorsten Wagner and Mark Hiner; original C code by Carsten Steger; detect-lines code by R. Balasubramanian),
located in `.../ciliaQ_Prep_jnh/ij_ridge_detector_steger/`. That code is licensed under the GNU General Public License version 2.0 or (at your option) any later version, and is therefore compatible with and here distributed under the
GNU General Public License version 3.0 of CiliaQ_Preparator. The original copyright and license headers are preserved in each file. The modifications make the output deterministic (reproducible) and fix bugs of the original code, some of which change the detected lines; a full list of changes, the original source,
and the link to the pull request where the determinism fixes were contributed back upstream are documented in the [README of that subpackage]
(https://github.com/hansenjn/CiliaQ_Preparator/blob/master/src/main/java/ciliaQ_Prep_jnh/ij_ridge_detector_steger/README.md).

Some functions (Hysteresis thresholding, Canny3D) in CiliaQ_Preparator require additional installation of the 3D ImageJ Suite to your FIJI / ImageJ distribution. How to install this 3D ImageJ Suite is explained in the installation instructions for CiliaQ at the CiliaQ wiki: https://github.com/hansenjn/CiliaQ/wiki/Installing-CiliaQ .

