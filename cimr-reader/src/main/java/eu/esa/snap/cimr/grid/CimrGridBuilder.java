package eu.esa.snap.cimr.grid;

import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;


public class CimrGridBuilder {


    private final GeometryBandToGridMapper mapper;


    public CimrGridBuilder(GeometryBandToGridMapper mapper) {
        this.mapper = mapper;
    }

    public CimrGridBandDataSource build(CimrBandDescriptor descriptor, CimrBand band, CimrGrid grid) {
        CimrGridBandDataSource target = CimrGridBandDataSource.createEmpty(grid.getWidth(), grid.getHeight(), descriptor.getRasterDataType());
        GridSampleValidator validator = GridSampleValidator.forDescriptor(descriptor);
        mapper.mapNearest(band, grid, target, validator);
        return target;
    }
}
