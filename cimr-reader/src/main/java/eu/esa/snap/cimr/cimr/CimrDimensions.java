package eu.esa.snap.cimr.cimr;

import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;

import java.util.HashMap;
import java.util.Map;


public class CimrDimensions {

    private final Map<String, Integer> values = new HashMap<>();

    public CimrDimensions() {
    }

    public CimrDimensions(Map<String,Integer> valMap) {
        this.values.putAll(valMap);
    }


    public static CimrDimensions from(NetcdfFile ncFile) {
        CimrDimensions dims = new CimrDimensions();
        for (Dimension dim : ncFile.getDimensions()) {
            dims.values.put(dim.getShortName(), dim.getLength());
        }
        return dims;
    }

    public int get(String name) {
        Integer v = values.get(name);
        if (v == null) {
            throw new IllegalArgumentException("Unknown dimension: " + name);
        }
        return v;
    }
}
