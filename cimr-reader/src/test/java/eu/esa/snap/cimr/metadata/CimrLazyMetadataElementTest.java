package eu.esa.snap.cimr.metadata;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;


public class CimrLazyMetadataElementTest {


    @Test
    @STTM("SNAP-4262")
    public void getNumElements_loadsChildrenAndAttributesOnce() throws IOException {
        CountingProvider provider = new CountingProvider(loadedElement());
        CimrLazyMetadataElement lazy = new CimrLazyMetadataElement("lazy", "variable/path", provider);

        assertEquals(0, provider.readCount);

        assertEquals(1, lazy.getNumElements());
        assertEquals(1, lazy.getNumAttributes());
        assertEquals(1, provider.readCount);

        assertEquals("child", lazy.getElementAt(0).getName());
        assertEquals("child", lazy.getElementNames()[0]);
        assertEquals("child", lazy.getElements()[0].getName());
        assertSame(lazy.getElement("child"), lazy.getElements()[0]);
        assertTrue(lazy.containsElement("child"));
        assertEquals(0, lazy.getElementIndex(lazy.getElement("child")));

        assertEquals("value", lazy.getAttributeString("attribute"));
        assertEquals("attribute", lazy.getAttributeAt(0).getName());
        assertEquals("attribute", lazy.getAttributeNames()[0]);
        assertEquals("attribute", lazy.getAttributes()[0].getName());
        assertSame(lazy.getAttribute("attribute"), lazy.getAttributes()[0]);
        assertTrue(lazy.containsAttribute("attribute"));
        assertEquals(0, lazy.getAttributeIndex(lazy.getAttribute("attribute")));

        lazy.getElementGroup();
        assertEquals(1, provider.readCount);
    }

    @Test
    @STTM("SNAP-4262")
    public void removeElement_loadsBeforeRemoving() throws IOException {
        CountingProvider provider = new CountingProvider(loadedElement());
        CimrLazyMetadataElement lazy = new CimrLazyMetadataElement("lazy", "variable/path", provider);

        MetadataElement child = lazy.getElement("child");

        assertTrue(lazy.removeElement(child));
        assertEquals(0, lazy.getNumElements());
        assertEquals(1, provider.readCount);
    }

    @Test
    @STTM("SNAP-4262")
    public void loadWithNullElementMarksElementAsLoaded() throws IOException {
        CountingProvider provider = new CountingProvider(null);
        CimrLazyMetadataElement lazy = new CimrLazyMetadataElement("lazy", "variable/path", provider);

        assertEquals(0, lazy.getNumElements());
        assertEquals(0, lazy.getNumAttributes());
        assertEquals(1, provider.readCount);

        assertFalse(lazy.containsElement("missing"));
        assertFalse(lazy.containsAttribute("missing"));
        assertEquals(1, provider.readCount);
    }

    @Test
    @STTM("SNAP-4262")
    public void loadWrapsIoException() {
        IOException failure = new IOException("read failed");
        CimrLazyMetadataElement lazy = new CimrLazyMetadataElement("lazy", "variable/path",
                path -> {
                    throw failure;
                });

        RuntimeException actual = assertThrows(RuntimeException.class, lazy::getNumElements);

        assertSame(failure, actual.getCause());
    }

    private static MetadataElement loadedElement() {
        MetadataElement loaded = new MetadataElement("loaded");
        loaded.addElement(new MetadataElement("child"));
        loaded.addAttribute(new MetadataAttribute("attribute", ProductData.createInstance("value"), true));
        return loaded;
    }

    private static class CountingProvider implements CimrMetadataProvider {


        private final MetadataElement element;
        private int readCount;


        private CountingProvider(MetadataElement element) {
            this.element = element;
        }

        @Override
        public MetadataElement readElement(String variablePath) {
            assertEquals("variable/path", variablePath);
            readCount++;
            return element;
        }
    }
}
