package eu.esa.snap.cimr.grid;


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
