package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.CimrReaderContext;
import eu.esa.snap.cimr.grid.GlobalGrid;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import eu.esa.snap.cimr.grid.LazyGridBandDataSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


public class CimrGridProduct {

    private final GlobalGrid globalGrid;
    private final Map<CimrBandDescriptor, GridBandDataSource> bands = new LinkedHashMap<>();


    public CimrGridProduct(GlobalGrid globalGrid) {
        this.globalGrid = globalGrid;
    }


    public GlobalGrid getGlobalGrid() {
        return globalGrid;
    }

    public void addBand(CimrBandDescriptor descriptor, GridBandDataSource dataSource) {
        bands.put(descriptor, dataSource);
    }

    public GridBandDataSource getBandData(CimrBandDescriptor descriptor) {
        return bands.get(descriptor);
    }

    public Map<CimrBandDescriptor, GridBandDataSource> getBands() {
        return Collections.unmodifiableMap(bands);
    }

    public int getBandCount() {
        return bands.size();
    }


    public static CimrGridProduct buildLazy(CimrReaderContext context, boolean useAverage) {
        GlobalGrid globalGrid = context.getGlobalGrid();
        CimrDescriptorSet descriptorSet = context.getDescriptorSet();

        CimrGridProduct product = new CimrGridProduct(globalGrid);

        for (CimrBandDescriptor desc : descriptorSet.getTiepointVariables()) {
            GridBandDataSource dataSource = new LazyGridBandDataSource(context, desc, useAverage);
            product.addBand(desc, dataSource);
        }

        for (CimrBandDescriptor desc : descriptorSet.getMeasurements()) {
            GridBandDataSource dataSource = new LazyGridBandDataSource(context, desc, useAverage);
            product.addBand(desc, dataSource);
        }

        return product;
    }
}
