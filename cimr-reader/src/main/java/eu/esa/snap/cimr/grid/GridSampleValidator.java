package eu.esa.snap.cimr.grid;

import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;


public interface GridSampleValidator {

    boolean isValid(double value);

    static GridSampleValidator forDescriptor(CimrBandDescriptor descriptor) {
        return value -> !descriptor.isInvalidSampleValue(value);
    }
}
