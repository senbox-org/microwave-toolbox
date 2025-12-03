package eu.esa.snap.cimr.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDescriptorKind;
import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
import eu.esa.snap.cimr.cimr.CimrFrequencyBand;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;


public class CimrConfigLoader {


    public static CimrDescriptorSet load(String jsonPath) throws IOException {
        try (InputStream in = CimrConfigLoader.class.getResourceAsStream(jsonPath)) {
            if (in == null) {
                throw new IOException("Config resource 'cimr-config.json' not found on classpath");
            }

            ObjectMapper mapper = new ObjectMapper();
            CimrConfig cfg = mapper.readValue(in, CimrConfig.class);

            List<CimrBandDescriptor> meas = cfg.getVariables().stream()
                    .map(e -> toDescriptor(e, CimrDescriptorKind.VARIABLE))
                    .toList();

            List<CimrBandDescriptor> tpVal = cfg.getTiepointVariables().stream()
                    .map(e -> toDescriptor(e, CimrDescriptorKind.TIEPOINT_VARIABLE))
                    .toList();

            List<CimrBandDescriptor> tpGeo = cfg.getGeometries().stream()
                    .map(e -> toDescriptor(e, CimrDescriptorKind.GEOMETRY))
                    .toList();

            return new CimrDescriptorSet(meas, tpGeo, tpVal);
        }
    }

    private static CimrBandDescriptor toDescriptor(CimrBandEntry e, CimrDescriptorKind kind) {
        CimrFrequencyBand band = CimrFrequencyBand.valueOf(e.band);
        return new CimrBandDescriptor(
                e.name,
                e.valueVarName,
                band,
                e.geometryNames,
                e.footprintVars,
                e.groupPath,
                e.feedIndex,
                kind,
                e.dimensions,
                e.dataType,
                e.unit,
                e.description
        );
    }
}
