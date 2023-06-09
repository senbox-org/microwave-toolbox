/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.fex.gpf;

import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;

import java.io.File;

@OperatorMetadata(alias = "SubGraph",
        category = "Input-Output",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        description = "Encapsulates a graph within a graph.")
public class SubGraphOp extends Operator {

    @Parameter
    private File graphFile;

    @Override
    public void initialize() throws OperatorException {
        throw new OperatorException("Please add a sub-graph file");
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(SubGraphOp.class);
        }
    }
}
