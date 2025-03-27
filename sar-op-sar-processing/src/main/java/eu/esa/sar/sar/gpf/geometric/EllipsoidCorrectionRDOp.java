/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.sar.gpf.geometric;

import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;

/**
 * This operator performs the same terrain correction as RangeDopplerGeocodingOp does except that it uses average
 * scene height from metadata instead of scene height from DEM.
 */

@OperatorMetadata(alias = "Ellipsoid-Correction-RD",
        category = "Radar/Geometric/Ellipsoid Correction",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Ellipsoid correction with RD method and average scene height")
public final class EllipsoidCorrectionRDOp extends RangeDopplerGeocodingOp {

    private static final String PRODUCT_SUFFIX = "_EC";

    @Override
    public void initialize() throws OperatorException {
        useAvgSceneHeight = true;
        super.initialize();

        nodataValueAtSea = false;
    }

    @Override
    protected String getProductSuffix() {
        return PRODUCT_SUFFIX;
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(EllipsoidCorrectionRDOp.class);
        }
    }
}
