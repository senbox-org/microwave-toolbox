package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import com.bc.ceres.core.ProgressMonitor;
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
