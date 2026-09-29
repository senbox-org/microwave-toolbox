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
package eu.esa.sar.fex.gpf.decisiontree;

import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.jexp.Namespace;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link ProductSetNamespace}.
 */
public class TestProductSetNamespace {

    @Test
    public void testPrefixForFirstProductIsIndexZero() {
        final Product p0 = new Product("p0", "T", 1, 1);
        final Product p1 = new Product("p1", "T", 1, 1);
        final ProductSetNamespace ns = new ProductSetNamespace(new Product[] { p0, p1 });

        assertEquals("$0.", ns.getProductNodeNamePrefix(p0));
    }

    @Test
    public void testPrefixForSecondProductIsIndexOne() {
        final Product p0 = new Product("p0", "T", 1, 1);
        final Product p1 = new Product("p1", "T", 1, 1);
        final ProductSetNamespace ns = new ProductSetNamespace(new Product[] { p0, p1 });

        assertEquals("$1.", ns.getProductNodeNamePrefix(p1));
    }

    @Test
    public void testPrefixForUnknownProductFallsBackToZero() {
        final Product p0 = new Product("known", "T", 1, 1);
        final Product unknown = new Product("unknown", "T", 1, 1);
        final ProductSetNamespace ns = new ProductSetNamespace(new Product[] { p0 });

        assertEquals("$0.", ns.getProductNodeNamePrefix(unknown));
    }

    @Test
    public void testNullProductThrows() {
        final ProductSetNamespace ns = new ProductSetNamespace(
                new Product[] { new Product("p", "T", 1, 1) });
        try {
            ns.getProductNodeNamePrefix(null);
            fail("Expected exception for null product");
        } catch (IllegalArgumentException | NullPointerException expected) {
            // Guardian throws IllegalArgumentException
        }
    }

    @Test
    public void testCreateNamespaceReturnsNonNull() {
        final Product p0 = new Product("p0", "T", 4, 4);
        final Product p1 = new Product("p1", "T", 4, 4);
        final ProductSetNamespace ns = new ProductSetNamespace(new Product[] { p0, p1 });

        final Namespace namespace = ns.createNamespace(0);

        assertNotNull(namespace);
    }
}
