package eu.esa.sar.calibration.gpf.support;

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.dataop.downloadable.XMLSupport;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.jdom2.Element;

import java.io.File;

/**
 * Loads the small RCM metadata fixtures under src/test/resources/rcm the same way the RCM reader does.
 */
public final class TestRCMNoiseModelSupport {

    private TestRCMNoiseModelSupport() {
    }

    /**
     * @param resource file name under /rcm
     * @param rename   new root element name (the reader renames per-polarisation files), or null
     */
    public static MetadataElement load(final String resource, final String rename) throws Exception {
        final File f = new File(TestRCMNoiseModelSupport.class.getResource("/rcm/" + resource).toURI());
        final Element root = XMLSupport.LoadXML(f.getAbsolutePath()).getRootElement();
        if (rename != null) {
            root.setName(rename);
        }
        final MetadataElement holder = new MetadataElement("holder");
        AbstractMetadataIO.AddXMLMetadata(root, holder);
        final MetadataElement elem = holder.getElementAt(0);
        holder.removeElement(elem);
        return elem;
    }
}
