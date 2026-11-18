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
package eu.esa.sar.sar.gpf.ui.geometric;

import eu.esa.sar.sar.gpf.geometric.GeolocationGridGeocodingOp;
import eu.esa.sar.sar.gpf.geometric.MosaicOp;
import eu.esa.sar.sar.gpf.geometric.RangeDopplerGeocodingOp;
import eu.esa.sar.sar.gpf.geometric.SARSimTerrainCorrectionOp;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.JComboBox;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The image-resampling combo box of each geocoding UI must offer exactly the kernels its
 * operator accepts (the @Parameter valueSet); other choices fail at run time.
 */
public class ResamplingKernelListTest {

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static List<String> valueSet(Class<?> opClass, String fieldName) throws NoSuchFieldException {
        Class<?> c = opClass;
        while (c != null) {
            try {
                final Field f = c.getDeclaredField(fieldName);
                return Arrays.asList(f.getAnnotation(Parameter.class).valueSet());
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static List<String> comboItems(Object ui, Class<?> declaringClass, String fieldName) throws Exception {
        final Field f = declaringClass.getDeclaredField(fieldName);
        f.setAccessible(true);
        final JComboBox<?> combo = (JComboBox<?>) f.get(ui);
        final List<String> items = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            items.add(String.valueOf(combo.getItemAt(i)));
        }
        return items;
    }

    @Test
    public void testGeolocationGridUiMatchesOperator() throws Exception {
        assertEquals(valueSet(GeolocationGridGeocodingOp.class, "imgResamplingMethod"),
                comboItems(new GeolocationGridGeocodingOpUI(), GeolocationGridGeocodingOpUI.class, "imgResamplingMethod"));
    }

    @Test
    public void testMosaicUiMatchesOperator() throws Exception {
        assertEquals(valueSet(MosaicOp.class, "resamplingMethod"),
                comboItems(new MosaicOpUI(), MosaicOpUI.class, "resamplingMethod"));
    }

    @Test
    public void testRangeDopplerUiMatchesOperator() throws Exception {
        assertEquals(valueSet(RangeDopplerGeocodingOp.class, "imgResamplingMethod"),
                comboItems(new RangeDopplerGeocodingOpUI(), RangeDopplerGeocodingOpUI.class, "imgResamplingMethod"));
    }

    @Test
    public void testSARSimTerrainCorrectionUiMatchesOperator() throws Exception {
        assertEquals(valueSet(SARSimTerrainCorrectionOp.class, "imgResamplingMethod"),
                comboItems(new SARSimTerrainCorrectionOpUI(), RangeDopplerGeocodingOpUI.class, "imgResamplingMethod"));
    }
}
