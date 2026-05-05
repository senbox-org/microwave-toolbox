package eu.esa.snap.cimr.cimr;

import com.bc.ceres.multilevel.MultiLevelModel;
import com.bc.ceres.multilevel.MultiLevelSource;
import com.bc.ceres.multilevel.support.AbstractMultiLevelSource;
import com.bc.ceres.multilevel.support.DefaultMultiLevelImage;
import com.bc.ceres.multilevel.support.DefaultMultiLevelModel;
import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.image.ResolutionLevel;

import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;


public class CimrGridMultiLevelSource extends AbstractMultiLevelSource {

    private static final int MLM_LEVEL_COUNT = 7;

    private final Band targetBand;
    private final GridBandDataSource gridDataSource;


    public CimrGridMultiLevelSource(MultiLevelModel multiLevelModel, Band targetBand,
                                    GridBandDataSource gridDataSource) {
        super(multiLevelModel);
        this.targetBand = targetBand;
        this.gridDataSource = gridDataSource;
    }

    @Override
    protected RenderedImage createImage(int level) {
        ResolutionLevel resLevel = ResolutionLevel.create(getModel(), level);
        return new CimrGridOpImage(targetBand, resLevel, gridDataSource);
    }

    public static void attachToBand(Band band, GridBandDataSource gridDataSource, CimrGrid grid) {
        AffineTransform imageToModel = grid.getProjection().getAffineTransform(grid);
        MultiLevelModel model = new DefaultMultiLevelModel(MLM_LEVEL_COUNT, imageToModel, grid.getWidth(), grid.getHeight());

        MultiLevelSource source = new CimrGridMultiLevelSource(model, band, gridDataSource);
        band.setSourceImage(new DefaultMultiLevelImage(source));
    }
}
