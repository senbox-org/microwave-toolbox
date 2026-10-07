package eu.esa.snap.cimr.netcdf;

import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.dataio.netcdf.util.DataTypeUtils;
import org.esa.snap.dataio.netcdf.util.ReaderUtils;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.nc2.Attribute;


public class NetcdfProductDataConverter {


    public ProductData toProductData(Attribute attribute) {
        if (attribute.isString()) {
            return ProductData.createInstance(attribute.getStringValue());
        }
        if (attribute.getValues() == null) {
            return ProductData.createInstance(attribute.toString());
        }
        return DataTypeUtils.createProductData(attribute);
    }

    public ProductData toProductData(Array array) {
        if (array == null) {
            return ProductData.createInstance("");
        }
        DataType dataType = array.getDataType();
        if (dataType == DataType.STRING) {
            return ProductData.createInstance(array.toString());
        }
        int productDataType = DataTypeUtils.getEquivalentProductDataType(dataType, dataType.isUnsigned(), false);
        if (productDataType != -1) {
            return ReaderUtils.createProductData(productDataType, array);
        }
        return ProductData.createInstance(array.toString());
    }
}
