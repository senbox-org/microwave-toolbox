package eu.esa.snap.cimr.grid;

import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;


public interface GridSampleValidator {

    GridSampleValidator NAN_ONLY = value -> !Double.isNaN(value);


    boolean isValid(double value);

    static GridSampleValidator forDescriptor(CimrBandDescriptor descriptor) {
        return value -> !descriptor.isInvalidSampleValue(value);
    }
}
