/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.ETADUtils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import java.util.Map;
import java.awt.*;


/**
 * Interface for ETAD correctors
 */
public interface Corrector {

    void initialize() throws OperatorException;
    void setTroposphericCorrectionRg(final boolean flag);
    void setIonosphericCorrectionRg(final boolean flag);
    void setGeodeticCorrectionRg(final boolean flag);
    void setDopplerShiftCorrectionRg(final boolean flag);
    void setGeodeticCorrectionAz(final boolean flag);
    void setBistaticShiftCorrectionAz(final boolean flag);
    void setFmMismatchCorrectionAz(final boolean flag);
    void setSumOfAzimuthCorrections(final boolean flag);
    void setSumOfRangeCorrections(final boolean flag);

    void setEtadUtils(final ETADUtils etadUtils);

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm,
                          final Operator op) throws OperatorException;
}
