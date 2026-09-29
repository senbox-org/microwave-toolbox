package eu.esa.snap.cimr.cimr;

import java.util.List;

public class CimrFootprints {

    private final List<CimrFootprintShape> shapes;
    private final List<Double> values;

    public CimrFootprints(List<CimrFootprintShape> shapes, List<Double> values) {
        this.shapes = shapes;
        this.values = values;
    }

    public List<CimrFootprintShape> getShapes() {
        return shapes;
    }

    public List<Double> getValues() {
        return values;
    }
}
