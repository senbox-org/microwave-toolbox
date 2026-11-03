package eu.esa.sar.calibration.gpf.support;

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TestS1IpfVersion {

    @Test
    public void plainIpfIdentifier() {
        final Product p = TestUtils.createProduct("GRD", 2, 2);
        AbstractMetadata.getAbstractedMetadata(p).setAttributeString(AbstractMetadata.ProcessingSystemIdentifier,
                "ESA Sentinel-1 IPF 003.71");
        assertEquals(3.71, S1IpfVersion.get(p), 1e-9);
    }

    @Test
    public void cogWrapperUsesNestedIpfEntry() {
        // CDSE COG products name the COGifier first; the IPF entry is nested in the manifest processing history
        final Product p = TestUtils.createProduct("GRD", 2, 2);
        AbstractMetadata.getAbstractedMetadata(p).setAttributeString(AbstractMetadata.ProcessingSystemIdentifier,
                "CloudFerro Sentinel-1 COGifier 001.00");
        final MetadataElement orig = AbstractMetadata.addOriginalProductMetadata(p.getMetadataRoot());
        final MetadataElement cog = new MetadataElement("processing");
        final MetadataElement cogSoftware = new MetadataElement("software");
        cogSoftware.setAttributeString("name", "Sentinel-1 COGifier");
        cogSoftware.setAttributeString("version", "001.00");
        final MetadataElement ipf = new MetadataElement("processing");
        final MetadataElement ipfSoftware = new MetadataElement("software");
        ipfSoftware.setAttributeString("name", "Sentinel-1 IPF");
        ipfSoftware.setAttributeString("version", "003.40");
        ipf.addElement(ipfSoftware);
        cog.addElement(cogSoftware);
        cog.addElement(ipf);
        orig.addElement(cog);
        assertEquals(3.40, S1IpfVersion.get(p), 1e-9);
    }

    @Test(expected = OperatorException.class)
    public void wrapperWithoutIpfEntryIsNotReadAsIpfVersion() {
        // without the nested IPF entry the COGifier's own version (1.00) must not pass for the IPF version
        final Product p = TestUtils.createProduct("GRD", 2, 2);
        AbstractMetadata.getAbstractedMetadata(p).setAttributeString(AbstractMetadata.ProcessingSystemIdentifier,
                "CloudFerro Sentinel-1 COGifier 001.00");
        S1IpfVersion.get(p);
    }
}
