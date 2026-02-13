package eu.esa.snap.cimr.cimr;

import java.util.List;


public class CimrDescriptorSet {

    private final List<CimrBandDescriptor> measurements;
    private final List<CimrBandDescriptor> geometries;
    private final List<CimrBandDescriptor> tiepointVariables;


    public CimrDescriptorSet(List<CimrBandDescriptor> measurements,
                             List<CimrBandDescriptor> geometries,
                             List<CimrBandDescriptor> tiepointVariables) {
        this.measurements = measurements;
        this.geometries = geometries;
        this.tiepointVariables = tiepointVariables;
    }

    public CimrBandDescriptor getGeometryByName(String name) {
        for (CimrBandDescriptor descriptor : this.geometries) {
            if (descriptor.getName().equals(name)) {
                return descriptor;
            }
        }
        return null;
    }

    public CimrBandDescriptor getTpVariableByName(String name) {
        for (CimrBandDescriptor descriptor : this.tiepointVariables) {
            if (descriptor.getName().equals(name)) {
                return descriptor;
            }
        }
        return null;
    }

    public CimrBandDescriptor getMeasurementByName(String name) {
        for (CimrBandDescriptor descriptor : this.measurements) {
            if (descriptor.getName().equals(name)) {
                return descriptor;
            }
        }
        return null;
    }

    public List<CimrBandDescriptor> getMeasurements() {
        return this.measurements;
    }

    public List<CimrBandDescriptor> getGeometries() {
        return this.geometries;
    }

    public List<CimrBandDescriptor> getTiepointVariables() {
        return this.tiepointVariables;
    }
}
