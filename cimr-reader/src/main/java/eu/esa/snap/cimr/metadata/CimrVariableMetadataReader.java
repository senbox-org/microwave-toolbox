package eu.esa.snap.cimr.metadata;

import eu.esa.snap.cimr.netcdf.NetcdfProductDataConverter;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import ucar.nc2.Variable;

import java.io.IOException;


class CimrVariableMetadataReader {


    private static final String VALUE_ATTRIBUTE_NAME = "value";

    private final NetcdfProductDataConverter productDataConverter;


    CimrVariableMetadataReader(NetcdfProductDataConverter productDataConverter) {
        this.productDataConverter = productDataConverter;
    }


    MetadataElement read(Variable variable) throws IOException {
        MetadataElement element = new MetadataElement(variable.getShortName());
        element.addAttribute(new MetadataAttribute(VALUE_ATTRIBUTE_NAME, productDataConverter.toProductData(variable.read()), true));
        return element;
    }
}