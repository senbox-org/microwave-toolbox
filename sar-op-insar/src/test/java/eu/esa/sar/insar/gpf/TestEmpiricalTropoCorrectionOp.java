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
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link EmpiricalTropoCorrectionOp}.
 */
public class TestEmpiricalTropoCorrectionOp {

    private static final String UNW_BAND = "Unw_Phase_ifg_01Jan2020_13Jan2020";
    private static final String COH_BAND = "coh_ifg_01Jan2020_13Jan2020";

    @Test
    public void testSpiCreatesOperator() {
        assertNotNull(new EmpiricalTropoCorrectionOp.Spi().createOperator());
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = EmpiricalTropoCorrectionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("EmpiricalTropoCorrection", md.alias());
        assertEquals("Radar/Interferometric/Filtering", md.category());
    }

    /**
     * Snaphu Import tags unwrapped bands as abs_phase, and the operator's own error
     * message points users at Snaphu Import - so abs_phase must be accepted.
     */
    @Test
    public void testAcceptsAbsPhaseBandFromSnaphuImport() {
        final Product target = runInitialize(createProduct(Unit.ABS_PHASE));

        final Band corrected = target.getBand(UNW_BAND);
        assertNotNull("unwrapped band missing from target product", corrected);
        assertEquals("the corrected band is still unwrapped phase", Unit.ABS_PHASE, corrected.getUnit());
    }

    /** Unwrappers that leave the band tagged as plain phase must keep working. */
    @Test
    public void testAcceptsPhaseBand() {
        final Product target = runInitialize(createProduct(Unit.PHASE));

        final Band corrected = target.getBand(UNW_BAND);
        assertNotNull("unwrapped band missing from target product", corrected);
        assertEquals(Unit.PHASE, corrected.getUnit());
    }

    /** Coherence bands pass through untouched so downstream masking still works. */
    @Test
    public void testCoherenceBandIsPassedThrough() {
        final Product target = runInitialize(createProduct(Unit.ABS_PHASE));

        final Band coherence = target.getBand(COH_BAND);
        assertNotNull("coherence band was dropped", coherence);
        assertEquals(Unit.COHERENCE, coherence.getUnit());
    }

    @Test
    public void testRejectsProductWithoutPhaseBand() {
        final Product source = new Product("noPhase", "type", 8, 8);
        source.addBand("Intensity_ifg", ProductData.TYPE_FLOAT32).setUnit(Unit.INTENSITY);

        try {
            runInitialize(source);
            fail("expected an OperatorException for a product with no unwrapped phase band");
        } catch (OperatorException e) {
            final String message = String.valueOf(e.getMessage());
            assertTrue("error message should name both accepted units, was: " + message,
                    message.contains(Unit.PHASE) && message.contains(Unit.ABS_PHASE));
        }
    }

    private static Product runInitialize(final Product source) {
        final EmpiricalTropoCorrectionOp op =
                (EmpiricalTropoCorrectionOp) new EmpiricalTropoCorrectionOp.Spi().createOperator();
        op.setSourceProduct(source);
        return op.getTargetProduct();
    }

    private static Product createProduct(final String phaseUnit) {
        final Product product = new Product("ifg", "type", 8, 8);
        product.addBand(UNW_BAND, ProductData.TYPE_FLOAT32).setUnit(phaseUnit);
        product.addBand(COH_BAND, ProductData.TYPE_FLOAT32).setUnit(Unit.COHERENCE);
        return product;
    }
}
