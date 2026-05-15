/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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
package org.csa.rstb.classification.gpf;

import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestCompactPolClassificationOps {

    @Test
    public void cp_classification_spi_and_metadata() {
        final CompactPolClassificationOp.Spi spi = new CompactPolClassificationOp.Spi();
        assertNotNull(spi.createOperator());

        final OperatorMetadata md = CompactPolClassificationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("CP-Classification", md.alias());
        assertEquals("Radar/Polarimetric/Compact Polarimetry", md.category());
    }

    @Test
    public void cp_supervised_classification_spi_and_metadata() {
        final CompactPolSupervisedClassificationOp.Spi spi = new CompactPolSupervisedClassificationOp.Spi();
        assertNotNull(spi.createOperator());

        final OperatorMetadata md = CompactPolSupervisedClassificationOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("CP-Supervised-Classification", md.alias());
        assertEquals("Radar/Polarimetric/Compact Polarimetry", md.category());
    }
}
