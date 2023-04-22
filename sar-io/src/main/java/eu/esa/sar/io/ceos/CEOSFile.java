package eu.esa.sar.io.ceos;

import eu.esa.sar.io.binary.BinaryDBReader;
import org.jdom2.Document;

public interface CEOSFile {

    default Document loadDefinitionFile(final String mission, final String fileName) {
        return BinaryDBReader.loadDefinitionFile(mission, fileName);
    }
}
