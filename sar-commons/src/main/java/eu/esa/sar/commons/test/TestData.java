/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.commons.test;

import org.esa.snap.engine_utilities.util.TestUtils;

import java.io.File;

/**
 * Paths to common SAR input data
 */
public class TestData {

    public final static String inputSAR = TestUtils.TESTDATA_ROOT;

    //ASAR
    public final static File inputASAR_IMM = new File(inputSAR + "ASAR/ASA_IMM.zip");
    public final static File inputASAR_APM = new File(inputSAR + "ASAR/ASA_APM.zip");
    public final static File inputASAR_WSM = new File(inputSAR + "ASAR/subset_1_of_ENVISAT-ASA_WSM_1PNPDE20080119_093446_000000852065_00165_30780_2977.dim");
    public final static File inputASAR_IMS = new File(inputSAR + "ASAR/subset_3_ASA_IMS_1PNUPA20031203_061259_000000162022_00120_09192_0099.dim");
    public final static File inputASAR_IMMSub = new File(inputSAR + "ASAR/subset_0_of_ENVISAT-ASA_IMM_1P_0739.dim");

    public final static File inputStackIMS = new File(inputSAR + "Stack/coregistered_stack.dim");

    //ERS
    public final static File inputERS_IMP = new File(inputSAR + "ERS/subset_0_of_ERS-1_SAR_PRI-ORBIT_32506_DATE__02-OCT-1997_14_53_43.dim");
    public final static File inputERS_IMS = new File(inputSAR + "ERS/subset_0_of_ERS-2_SAR_SLC-ORBIT_10249_DATE__06-APR-1997_03_09_34.dim");

    //RS2
    public final static File inputRS2_SQuad = new File(inputSAR + "RS2/RS2-standard-quad.zip");

    //RCM

    //QuadPol
    public final static File inputQuad = new File(inputSAR + "QuadPol/QuadPol_subset_0_of_RS2-SLC-PDS_00058900.dim");
    public final static File inputQuadFullStack = new File(inputSAR + "QuadPolStack/RS2-Quad_Pol_Stack.dim");
    public final static File inputC3Stack = new File(inputSAR + "QuadPolStack/RS2-C3-Stack.dim");
    public final static File inputT3Stack = new File(inputSAR + "QuadPolStack/RS2-T3-Stack.dim");

    //ALOS
    public final static File inputALOS1_1 = new File(inputSAR + "ALOS/subset_0_of_ALOS-H1_1__A-ORBIT__ALPSRP076360690.dim");
    public final static File inputALOS_Zip = new File(inputSAR + "ALOS/ALPSRS267172700-L1.5.zip");

    //S1
    public final static File inputS1_GRD = new File(inputSAR + "S1/S1A_S1_GRDM_1SDV_20140607T172812_20140607T172836_000947_000EBD_7543.zip");
    public final static File inputS1_GRDSubset = new File(inputSAR + "S1/subset_0_of_subset_1_of_S1A_S1_GRDH_1SDH_20150828T165902_20150828T165921_007466_00A4B2_84A5.dim");
    public final static File inputS1_SLC = new File(inputSAR + "S1/S1A_IW_SLC__1SDV_20180620T222319_20180620T222347_022446_026E52_B227.zip");
    public final static File inputS1_StripmapSLC = new File(inputSAR + "S1/subset_2_S1A_S1_SLC__1SSV_20140807T142342_20140807T142411_001835_001BC1_05AA.dim");
}
