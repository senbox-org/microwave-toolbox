package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.CimrReaderContext;
import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import eu.esa.snap.cimr.grid.LazyGridBandDataSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


public class CimrGridProduct {

    private final CimrGrid cimrGrid;
    private final Map<CimrBandDescriptor, GridBandDataSource> bands = new LinkedHashMap<>();


    public CimrGridProduct(CimrGrid cimrGrid) {
        this.cimrGrid = cimrGrid;
    }


    public CimrGrid getGlobalGrid() {
        return cimrGrid;
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
        CimrGrid cimrGrid = context.getGlobalGrid();
        CimrDescriptorSet descriptorSet = context.getDescriptorSet();

        CimrGridProduct product = new CimrGridProduct(cimrGrid);

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
