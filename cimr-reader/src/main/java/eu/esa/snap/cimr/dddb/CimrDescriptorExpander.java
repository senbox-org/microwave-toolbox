package eu.esa.snap.cimr.dddb;

import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDescriptorKind;
import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
import eu.esa.snap.cimr.cimr.CimrFrequencyBand;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class CimrDescriptorExpander {


    private final CimrDDDB dddb;


    public CimrDescriptorExpander(CimrDDDB dddb) {
        this.dddb = dddb;
    }


    public CimrDescriptorSet expand(CimrProductDescriptor productDescriptor) throws IOException {
        CimrBandDefinition[] bandDefinitions = dddb.getBandDefinitions(productDescriptor);

        List<CimrBandDescriptor> measurements = new ArrayList<>();
        List<CimrBandDescriptor> geometries = new ArrayList<>();
        List<CimrBandDescriptor> tiepointVariables = new ArrayList<>();

        for (String descriptorFileName : productDescriptor.getDescriptorFiles()) {
            CimrVariableFamily[] descriptorFamilies = dddb.getDescriptorFile(productDescriptor, descriptorFileName);
            for (CimrVariableFamily family : descriptorFamilies) {
                expandFamily(family, bandDefinitions, measurements, geometries, tiepointVariables);
            }
        }

        return new CimrDescriptorSet(measurements, geometries, tiepointVariables);
    }

    private void expandFamily(CimrVariableFamily family,
                              CimrBandDefinition[] bands,
                              List<CimrBandDescriptor> measurements,
                              List<CimrBandDescriptor> geometries,
                              List<CimrBandDescriptor> tiepointVariables) {
        CimrDescriptorKind kind = CimrDescriptorKind.valueOf(family.getKind());
        for (CimrBandDefinition band : bands) {
            int feedCount = family.isExpandFeeds() ? band.getFeedCount() : 1;
            for (int feedIndex = 0; feedIndex < feedCount; feedIndex++) {
                CimrBandDescriptor descriptor = createDescriptor(family, band, feedIndex, kind);
                switch (kind) {
                    case VARIABLE -> measurements.add(descriptor);
                    case GEOMETRY -> geometries.add(descriptor);
                    case TIEPOINT_VARIABLE -> tiepointVariables.add(descriptor);
                }
            }
        }
    }

    private CimrBandDescriptor createDescriptor(CimrVariableFamily family,
                                                CimrBandDefinition band,
                                                int feedIndex,
                                                CimrDescriptorKind kind) {
        String bandName = band.getName();
        int feedNumber = feedIndex + 1;
        String descriptorName = descriptorName(bandName, family.getValueVarName(), feedNumber);

        return new CimrBandDescriptor(
                descriptorName,
                family.getValueVarName(),
                CimrFrequencyBand.valueOf(bandName),
                referenceNames(bandName, feedNumber, family.getGeometryVariables()),
                referenceNames(bandName, feedNumber, family.getFootprintVariables()),
                resolve(family.getGroupPath(), band),
                feedIndex,
                kind,
                resolve(family.getDimensions(), band),
                family.getDataType(),
                family.getUnit(),
                resolve(family.getDescription(), band)
        );
    }

    private static String[] referenceNames(String bandName, int feedNumber, String[] variableNames) {
        if (variableNames == null || variableNames.length == 0) {
            return null;
        }
        String[] names = new String[variableNames.length];
        for (int i = 0; i < variableNames.length; i++) {
            names[i] = descriptorName(bandName, variableNames[i], feedNumber);
        }
        return names;
    }

    private static String descriptorName(String bandName, String variableName, int feedNumber) {
        return bandName + "_" + variableName + "_feed" + feedNumber;
    }

    private static String[] resolve(String[] values, CimrBandDefinition band) {
        String[] resolved = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            resolved[i] = resolve(values[i], band);
        }
        return resolved;
    }

    private static String resolve(String value, CimrBandDefinition band) {
        return value
                .replace("{band}", band.getName())
                .replace("{feedDimension}", band.getFeedDimension())
                .replace("{sampleDimension}", band.getSampleDimension())
                .replace("{tiePointDimension}", band.getTiePointDimension());
    }
}
