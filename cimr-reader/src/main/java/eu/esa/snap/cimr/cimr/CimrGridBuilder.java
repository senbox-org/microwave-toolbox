package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.grid.CimrBand;
import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.grid.CimrGridBandDataSource;
import eu.esa.snap.cimr.grid.GeometryBandToGridMapper;


public class CimrGridBuilder {

    private final GeometryBandToGridMapper mapper;


    public CimrGridBuilder(GeometryBandToGridMapper mapper) {
        this.mapper = mapper;
    }

    public CimrGridBandDataSource build(CimrBand band, CimrGrid grid, boolean useAverage) {
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(grid.getWidth(), grid.getHeight());
        if (useAverage) {
            mapper.mapAverage(band, grid, target);
        } else {
            mapper.mapNearest(band, grid, target);
        }
        return target;
    }
}
