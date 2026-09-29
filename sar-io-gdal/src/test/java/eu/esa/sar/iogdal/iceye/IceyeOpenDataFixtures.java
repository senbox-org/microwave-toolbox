/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.iceye;

import eu.esa.sar.commons.test.TestData;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;

/**
 * Locates the committed ICEYE Open Data COG fixtures and the full-size
 * products.
 * <p>
 * The fixtures are tile-aligned windows carved out of a real delivery; see
 * {@code src/test/resources/eu/esa/sar/iogdal/iceye/opendata/README.md} for how
 * they are built and what is guaranteed about them.
 *
 * @author Luis Veci
 */
public class IceyeOpenDataFixtures {

    private static final String RESOURCE_DIR = "/eu/esa/sar/iogdal/iceye/opendata/";
    private static final String BASE = "ICEYE_6Q31WW_20251110T182058Z_7010715_X56_SLEDF_crop_";

    /** The GRD window is 1024 (azimuth) x 512 (range) in TIFF orientation. */
    public static final int GRD_RANGE_SAMPLES = 512;
    public static final int GRD_AZIMUTH_LINES = 1024;

    public static final int SLC_RANGE_SAMPLES = 512;
    public static final int SLC_AZIMUTH_LINES = 512;

    /** Full-size products, if they have been synced to the shared test tree. */
    private static final String OPEN_DATA_DIR = TestData.inputSAR + "Iceye/OpenData/";
    private static final String FULL_BASE = "ICEYE_6Q31WW_20251110T182058Z_7010715_X56_SLEDF_";

    public static File grdCog() {
        return resource(BASE + "GRD.tif");
    }

    public static File grdStacItem() {
        return resource(BASE + "GRD.json");
    }

    public static File slcCog() {
        return resource(BASE + "SLC.tif");
    }

    public static File slcStacItem() {
        return resource(BASE + "SLC.json");
    }

    public static File fullGrdCog() {
        return new File(OPEN_DATA_DIR + FULL_BASE + "GRD.tif");
    }

    public static File fullSlcCog() {
        return new File(OPEN_DATA_DIR + FULL_BASE + "SLC.tif");
    }

    public static File fullGrdStacItem() {
        return new File(OPEN_DATA_DIR + FULL_BASE + "GRD.json");
    }

    public static File fullSlcStacItem() {
        return new File(OPEN_DATA_DIR + FULL_BASE + "SLC.json");
    }

    public static JSONObject parse(final String json) throws Exception {
        return (JSONObject) new JSONParser().parse(json);
    }

    /** The whole STAC item, not just its properties - for cross-checks against bbox and assets. */
    public static JSONObject stacItem(final File file) throws Exception {
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file.toPath())) {
            return (JSONObject) new JSONParser().parse(reader);
        }
    }

    private static File resource(final String name) {
        final URL url = IceyeOpenDataFixtures.class.getResource(RESOURCE_DIR + name);
        if (url == null) {
            throw new IllegalStateException("test fixture " + RESOURCE_DIR + name + " is not on the classpath");
        }
        try {
            return Paths.get(url.toURI()).toFile();
        } catch (Exception e) {
            throw new IllegalStateException("cannot resolve " + url, e);
        }
    }
}
