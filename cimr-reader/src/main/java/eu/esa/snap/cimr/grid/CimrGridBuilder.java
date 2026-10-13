package eu.esa.snap.cimr.grid;

import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;


public class CimrGridBuilder {


    private final GeometryBandToGridMapper mapper;


    public CimrGridBuilder(GeometryBandToGridMapper mapper) {
        this.mapper = mapper;
    }


    public CimrGridBandDataSource build(CimrBand band, CimrGrid grid) {
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(grid.getWidth(), grid.getHeight());
        mapper.mapNearest(band, grid, target);
        return target;
    }

    public CimrGridBandDataSource build(CimrBandDescriptor descriptor, CimrBand band, CimrGrid grid) {
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(grid.getWidth(), grid.getHeight());
        GridSampleValidator validator = GridSampleValidator.forDescriptor(descriptor);
        mapper.mapNearest(band, grid, target, validator);
        return target;
    }
}
