package eu.esa.snap.cimr.metadata;

import org.esa.snap.core.datamodel.MetadataElement;

import java.io.IOException;


interface CimrMetadataProvider {


    MetadataElement readElement(String variablePath) throws IOException;
}