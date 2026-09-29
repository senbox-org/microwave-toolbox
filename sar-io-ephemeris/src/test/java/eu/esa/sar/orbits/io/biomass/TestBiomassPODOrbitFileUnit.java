/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 */
package eu.esa.sar.orbits.io.biomass;

import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for BIOMASS AUX_ORB filename validity-window parsing. Confirms that the FFS v3
 * BIOMASS filename layout (which lacks the Sentinel-1 OPOD_ field, so the 'V' offset differs)
 * is parsed correctly.
 */
public class TestBiomassPODOrbitFileUnit {

    private static ProductData.UTC utc(final String iso) throws Exception {
        return ProductData.UTC.parse(iso, ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    public void inRange_simpleName() throws Exception {
        final String name = "BIO_OPER_AUX_ORB____20250502T120000_V20250501T000000_20250502T000000.EOF";
        assertTrue(BiomassPODOrbitFile.isWithinRange(name, utc("2025-05-01 12:00:00")));
    }

    @Test
    public void outOfRange_before() throws Exception {
        final String name = "BIO_OPER_AUX_ORB____20250502T120000_V20250501T000000_20250502T000000.EOF";
        assertFalse(BiomassPODOrbitFile.isWithinRange(name, utc("2025-04-30 23:59:59")));
    }

    @Test
    public void outOfRange_after() throws Exception {
        final String name = "BIO_OPER_AUX_ORB____20250502T120000_V20250501T000000_20250502T000000.EOF";
        assertFalse(BiomassPODOrbitFile.isWithinRange(name, utc("2025-05-02 00:00:01")));
    }

    @Test
    public void inRange_versionedName() throws Exception {
        // FFS v3 sometimes appends a version (e.g. "_0001")
        final String name = "BIO_OPER_AUX_ORB____20250502T120000_V20250501T000000_20250502T000000_0001.EOF";
        assertTrue(BiomassPODOrbitFile.isWithinRange(name, utc("2025-05-01 06:00:00")));
    }

    @Test
    public void inRange_zippedName() throws Exception {
        final String name = "BIO_OPER_AUX_ORB____20250502T120000_V20250501T000000_20250502T000000.EOF.zip";
        assertTrue(BiomassPODOrbitFile.isWithinRange(name, utc("2025-05-01 12:00:00")));
    }

    @Test
    public void malformedName_returnsFalse() throws Exception {
        assertFalse(BiomassPODOrbitFile.isWithinRange("not-an-orbit-file.EOF", utc("2025-05-01 12:00:00")));
    }
}
