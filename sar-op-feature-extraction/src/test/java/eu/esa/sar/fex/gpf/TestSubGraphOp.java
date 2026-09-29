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
package eu.esa.sar.fex.gpf;

import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link SubGraphOp}.
 * This operator is a stub that always fails initialisation unless a sub-graph
 * file has been configured; these tests pin down that contract.
 */
public class TestSubGraphOp {

    @Test
    public void testSpiCreatesOperator() {
        final SubGraphOp op = (SubGraphOp) new SubGraphOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = SubGraphOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("SubGraph", md.alias());
        assertEquals("Input-Output", md.category());
    }

    @Test
    public void testInitializeAlwaysThrows() {
        final SubGraphOp op = new SubGraphOp();
        try {
            op.initialize();
            fail("SubGraphOp must throw when initialised without a sub-graph file");
        } catch (OperatorException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("sub-graph"));
        }
    }
}
