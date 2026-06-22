package eu.esa.snap.cimr.config;

import java.util.List;


public class CimrConfig {

    private List<CimrBandEntry> variables;
    private List<CimrBandEntry> tiepointVariables;
    private List<CimrBandEntry> geometries;


    public List<CimrBandEntry> getVariables() {
        return variables;
    }

    public List<CimrBandEntry> getTiepointVariables() {
        return tiepointVariables;
    }

    public List<CimrBandEntry> getGeometries() {
        return geometries;
    }

    public void setVariables(List<CimrBandEntry> variables) {
        this.variables = variables;
    }

    public void setTiepointVariables(List<CimrBandEntry> tiepointVariables) {
        this.tiepointVariables = tiepointVariables;
    }

    public void setGeometries(List<CimrBandEntry> geometries) {
        this.geometries = geometries;
    }
}
