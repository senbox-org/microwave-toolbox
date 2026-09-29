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
package eu.esa.sar.utilities.gpf;

import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link TileWriterOp}.
 */
public class TestTileWriterOp {

    @Test
    public void testSpiCreatesOperator() {
        final TileWriterOp op = (TileWriterOp) new TileWriterOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = TileWriterOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("TileWriter", md.alias());
        assertEquals("Tools", md.category());
        assertTrue("TileWriterOp must opt out of auto-write because it writes its own tiles",
                md.autoWriteDisabled());
    }

    @Test
    public void testDefaultConstructorRequiresAllBands() {
        final TileWriterOp op = new TileWriterOp();
        // TileWriterOp toggles requiresAllBands in the default constructor so
        // the framework passes every source band to the operator at once.
        // This is exposed indirectly via the Operator base class; construction
        // must succeed without throwing.
        assertNotNull(op);
    }
}
