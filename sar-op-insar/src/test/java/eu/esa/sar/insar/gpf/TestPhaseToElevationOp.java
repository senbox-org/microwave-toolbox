/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for {@link PhaseToElevationOp}.
 */
public class TestPhaseToElevationOp {

    @Test
    public void testSpiCreatesOperator() {
        final PhaseToElevationOp op = (PhaseToElevationOp) new PhaseToElevationOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = PhaseToElevationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("PhaseToElevation", md.alias());
        assertEquals("Radar/Interferometric/Products", md.category());
    }

    /**
     * The default must stay on the DEM-seeded path so that graphs written before
     * the Schwabisch method was folded in keep producing the same result.
     */
    @Test
    public void testMethodParameterDefaultsToDemSeed() throws Exception {
        final Parameter p = PhaseToElevationOp.class.getDeclaredField("method").getAnnotation(Parameter.class);
        assertNotNull(p);
        assertEquals(PhaseToElevationOp.METHOD_DEM_SEED, p.defaultValue());
        assertArrayEquals(new String[]{PhaseToElevationOp.METHOD_DEM_SEED, PhaseToElevationOp.METHOD_SCHWABISCH},
                p.valueSet());
    }

    /**
     * The Schwabisch parameters must accept the same value sets as the deprecated
     * PhaseToHeight operator so migrated graphs bind without adjustment.
     */
    @Test
    public void testSchwabischParametersMatchDeprecatedOperator() throws Exception {
        assertValueSet("nPoints", "200", "100", "200", "300", "400", "500");
        assertValueSet("nHeights", "3", "2", "3", "4", "5");
        assertValueSet("degree1D", "2", "1", "2", "3", "4", "5");
        assertValueSet("degree2D", "5", "1", "2", "3", "4", "5", "6", "7", "8");
        assertValueSet("orbitDegree", "3", "2", "3", "4", "5");
    }

    private static void assertValueSet(final String fieldName, final String defaultValue,
                                       final String... valueSet) throws NoSuchFieldException {
        final Field field = PhaseToElevationOp.class.getDeclaredField(fieldName);
        final Parameter p = field.getAnnotation(Parameter.class);
        assertNotNull(fieldName + " is not a @Parameter", p);
        assertEquals(fieldName + " default", defaultValue, p.defaultValue());
        assertArrayEquals(fieldName + " value set", valueSet, p.valueSet());
    }

    /** Snaphu Import tags unwrapped bands abs_phase regardless of their name. */
    @Test
    public void testBandDiscoveryPrefersAbsPhaseUnit() {
        final Product product = new Product("p", "t", 4, 4);
        final Band intensity = addBand(product, "Intensity_ifg", Unit.INTENSITY);
        final Band unwrapped = addBand(product, "some_renamed_band", Unit.ABS_PHASE);

        final Band found = PhaseToElevationOp.findUnwrappedPhaseBand(product.getBands());
        assertSame(unwrapped, found);
        assertNotNull(intensity);
    }

    /** Legacy products without the unit set are still matched on the name prefix. */
    @Test
    public void testBandDiscoveryFallsBackToNamePrefix() {
        final Product product = new Product("p", "t", 4, 4);
        addBand(product, "Intensity_ifg", Unit.INTENSITY);
        final Band unwrapped = addBand(product, "Unw_Phase_ifg_01Jan2020_13Jan2020", null);

        assertSame(unwrapped, PhaseToElevationOp.findUnwrappedPhaseBand(product.getBands()));
    }

    /** A band with a null unit must not NPE the scan. */
    @Test
    public void testBandDiscoveryReturnsNullWhenNoUnwrappedBand() {
        final Product product = new Product("p", "t", 4, 4);
        addBand(product, "Intensity_ifg", Unit.INTENSITY);
        addBand(product, "i_ifg", null);

        assertNull(PhaseToElevationOp.findUnwrappedPhaseBand(product.getBands()));
    }

    /**
     * The parameters the operator declares must be exactly what PhaseToElevationOpUI
     * writes into the parameter map, otherwise the dialog silently drops settings.
     */
    @Test
    public void testAllUiWrittenParametersAreDeclared() {
        final List<String> uiParameters = Arrays.asList(
                "method", "orbitDegree", "nPoints", "nHeights", "degree1D", "degree2D",
                "demName", "demResamplingMethod", "externalDEMFile", "externalDEMNoDataValue");

        for (String name : uiParameters) {
            try {
                assertNotNull("field '" + name + "' must carry @Parameter",
                        PhaseToElevationOp.class.getDeclaredField(name).getAnnotation(Parameter.class));
            } catch (NoSuchFieldException e) {
                throw new AssertionError("PhaseToElevationOpUI writes parameter '" + name
                        + "' but PhaseToElevationOp does not declare it", e);
            }
        }
    }

    private static Band addBand(final Product product, final String name, final String unit) {
        final Band band = product.addBand(name, ProductData.TYPE_FLOAT32);
        band.setUnit(unit);
        return band;
    }
}
