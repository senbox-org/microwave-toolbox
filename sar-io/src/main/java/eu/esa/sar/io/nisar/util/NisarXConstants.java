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
package eu.esa.sar.io.nisar.util;

/**
 * Constants used by the NISAR reader. Only fields that are actually referenced from
 * the reader code are kept here — the previous version of this class carried 50+
 * unused constants copy-pasted from another mission, which made it hard to tell
 * which were load-bearing.
 */
public final class NisarXConstants {

    public static final String NISAR_PLUGIN_DESCRIPTION = "NISAR Products";

    /**
     * Filename prefixes the plugin will accept. {@code NISAR} is the operational name;
     * {@code WINNIP} is the JPL pre-launch simulation product family that uses the
     * same HDF5 schema, retained so JPL sample data also opens.
     */
    public static final String[] NISAR_FILE_PREFIXES = new String[]{"NISAR", "WINNIP"};

    /** Variable name in {@code identification} for the product type ("RSLC", "GSLC", ...). */
    public static final String PRODUCT_TYPE = "productType";

    /** Variable name in {@code identification} for the SAR processing level ("SLC", "DGM", ...). */
    public static final String SPH_DESCRIPTOR = "product_level";

    /** Variable name in {@code identification} for the mission ID string. */
    public static final String MISSION = "missionId";

    /** Polarisation variable name (legacy / global-attribute lookup). */
    public static final String MDS1_TX_RX_POLAR = "polarization";

    /** SAMPLE_TYPE value for complex products (RSLC, GSLC). */
    public static final String COMPLEX = "COMPLEX";

    /** SAMPLE_TYPE value for detected products (GCOV, GUNW etc.). */
    public static final String DETECTED = "DETECTED";

    /** SLC processing-level discriminator. */
    public static final String SLC = "slc";

    /** Format names exposed by {@code NisarProductReaderPlugIn}. */
    public static final String[] NISAR_FORMAT_NAMES = {"NISAR"};

    /** Default file extensions exposed by {@code NisarProductReaderPlugIn}. */
    public static final String[] NISAR_FORMAT_FILE_EXTENSIONS = {".h5"};

    private NisarXConstants() {
        // utility class
    }

    public static String[] getNisarFormatNames() {
        return NISAR_FORMAT_NAMES.clone();
    }

    public static String[] getNisarFormatFileExtensions() {
        return NISAR_FORMAT_FILE_EXTENSIONS.clone();
    }
}
