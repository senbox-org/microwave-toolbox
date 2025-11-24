package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.grid.CimrBand;
import eu.esa.snap.cimr.grid.GlobalGrid;
import eu.esa.snap.cimr.grid.GlobalGridBandDataSource;
import eu.esa.snap.cimr.grid.GeometryBandToGridMapper;


public class CimrGridBuilder {

    private final GeometryBandToGridMapper mapper;


    public CimrGridBuilder(GeometryBandToGridMapper mapper) {
        this.mapper = mapper;
    }

    public GlobalGridBandDataSource build(CimrBand band, GlobalGrid grid, boolean useAverage) {
        GlobalGridBandDataSource target = GlobalGridBandDataSource.createEmpty(grid.getWidth(), grid.getHeight());
        if (useAverage) {
            mapper.mapAverage(band, grid, target);
        } else {
            mapper.mapNearest(band, grid, target);
        }
        return target;
    }
}
