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
* Date: September 29, 2019 (This Version: November 4, 2026)
*   
* For any questions please feel free to contact me (jan.hansen@uni-bonn.de).
* =============================================================================== */

/** A setting that cannot work for this channel or image. 
 *	The message is written for the user and is shown
 *  as it is, without a stack trace. Added in V0.2.1. */
public class SettingsRejectedException extends Exception {
	private static final long serialVersionUID = 1L;
	public SettingsRejectedException(String message) { super(message); }
}
