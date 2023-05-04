package eu.esa.sar.sar.gpf.pyrate;

/*
    For use with PyRate. Purpose of this operator is to take a given input folder from SNAPHU unwrap,
    unwrap all interferograms in the folder, and then import back into one singular product.

    Outputs a single product containing all the unwrapped bands. Needs to have SNAPHU Import applied after to
    preserve geocoding.
 */


import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;

import java.io.File;


@OperatorMetadata(alias = "BatchSnaphuUnwrapOp",
        category = "Radar/Interferometric/Unwrapping",
        authors = "Alex McVittie",
        version = "1.0",
        copyright = "Copyright (C) 2023 SkyWatch Space Applications Inc.",
        autoWriteDisabled = true,
        description = "Downloads and executes SNAPHU on a ")

public class BatchSnaphuUnwrapOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @Parameter(description = "Directory that the Snaphu Export operator wrote to",
            label="Snaphu Export folder")
    protected File snaphuProcessingLocation;

    @Parameter(description = "Directory to install the SNAPHU binary to",
            label="Snaphu Install Location")
    protected File snaphuInstallLocation;


    @Override
    public void initialize() throws OperatorException {

    }
}
