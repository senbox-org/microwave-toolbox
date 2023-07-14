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

import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.esa.snap.runtime.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.prefs.Preferences;

/**
 * Utilities for Operator unit tests
 */
public class SARTests {

    public final static String inputPathProperty = TestUtils.TESTDATA_ROOT;

    private static final String SAR_TESTS = "s1tbx.tests";

    private static final Preferences testPreferences = Config.instance(SAR_TESTS).load().preferences();

    public final static File[] rootPathsTerraSarX = loadFilePath("test.rootPathTerraSarX", TestData.inputSAR + "TerraSAR-X");
    public final static File[] rootPathsASAR = loadFilePath("test.rootPathASAR", TestData.inputSAR + "ASAR");
    public final static File[] rootPathsRadarsat2 = loadFilePath("test.rootPathASAR", TestData.inputSAR + "RS2");
    public final static File[] rootPathsRadarsat1 = loadFilePath("test.rootPathRadarsat1", TestData.inputSAR + "RS1");
    public final static File[] rootPathsSentinel1 = loadFilePath("test.rootPathSentinel1", TestData.inputSAR + "S1");
    public final static File[] rootPathsERS = loadFilePath("test.rootPathERS", TestData.inputSAR + "ERS");
    public final static File[] rootPathsJERS = loadFilePath("test.rootPathJERS", TestData.inputSAR + "JERS");
    public final static File[] rootPathsALOS = loadFilePath("test.rootPathALOS", TestData.inputSAR + "ALOS");
    public final static File[] rootPathsALOS2 = loadFilePath("test.rootPathALOS2", TestData.inputSAR + "ALOS2");
    public final static File[] rootPathsCosmoSkymed = loadFilePath("test.rootPathCosmoSkymed", TestData.inputSAR + "Cosmo");
    public final static File[] rootPathsIceye = loadFilePath("test.rootPathIceye", TestData.inputSAR + "Iceye");
    public final static File[] rootPathsStriX = loadFilePath("test.rootPathStriX", TestData.inputSAR + "Synpective");
    public final static File[] rootPathsK5 = loadFilePath("test.rootPathK5", TestData.inputSAR + "K5");

    public static int subsetX = 0;
    public static int subsetY = 0;
    public static int subsetWidth = 0;
    public static int subsetHeight = 0;

    public static int maxIteration = 0;

    public static boolean canTestReadersOnAllProducts = false;
    public static boolean canTestProcessingOnAllProducts = false;

    static {
        if (testPreferences != null) {

            subsetX = Integer.parseInt(testPreferences.get("test.subsetX", "100"));
            subsetY = Integer.parseInt(testPreferences.get("test.subsetY", "100"));
            subsetWidth = Integer.parseInt(testPreferences.get("test.subsetWidth", "100"));
            subsetHeight = Integer.parseInt(testPreferences.get("test.subsetHeight", "100"));

            maxIteration = Integer.parseInt(testPreferences.get("test.maxProductsPerRootFolder", "100"));
            String testReadersOnAllProducts = testPreferences.get("test.ReadersOnAllProducts", "true");
            String testProcessingOnAllProducts = testPreferences.get("test.ProcessingOnAllProducts", "true");

            canTestReadersOnAllProducts = testReadersOnAllProducts != null && testReadersOnAllProducts.equalsIgnoreCase("true");
            canTestProcessingOnAllProducts = testProcessingOnAllProducts != null && testProcessingOnAllProducts.equalsIgnoreCase("true");
        }
    }

    public static File[] loadFilePath(final String id, final String defaultPath) {
        if (testPreferences == null)
            return new File[]{};

        final List<File> fileList = new ArrayList<>(3);
        final String pathsStr = testPreferences.get(id, "");
        final StringTokenizer st = new StringTokenizer(pathsStr, ",");
        while (st.hasMoreTokens()) {
            fileList.add(new File(st.nextToken()));
        }
        if(fileList.isEmpty()) {
            fileList.add(new File(defaultPath));
        }
        return fileList.toArray(new File[0]);
    }

    public static File[] loadFilePath(final String defaultPath) {
        final List<File> fileList = new ArrayList<>(3);
        fileList.add(new File(defaultPath));
        return fileList.toArray(new File[0]);
    }

    public static TestProcessor createTestProcessor() {
        return new TestProcessor(subsetX, subsetY, subsetWidth, subsetHeight,
                maxIteration, canTestReadersOnAllProducts, canTestProcessingOnAllProducts);
    }
}
