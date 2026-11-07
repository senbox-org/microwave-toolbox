package eu.esa.snap.cimr.metadata;

import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductNode;
import org.esa.snap.core.datamodel.ProductNodeGroup;

import java.io.IOException;


class CimrLazyMetadataElement extends MetadataElement {


    private final String variablePath;
    private final CimrMetadataProvider provider;
    private boolean loaded;


    CimrLazyMetadataElement(String name, String variablePath, CimrMetadataProvider provider) {
        super(name);
        this.variablePath = variablePath;
        this.provider = provider;
    }


    @Override
    public ProductNodeGroup<MetadataElement> getElementGroup() {
        ensureLoaded();
        return super.getElementGroup();
    }

    @Override
    public boolean removeElement(MetadataElement element) {
        ensureLoaded();
        return super.removeElement(element);
    }

    @Override
    public int getNumElements() {
        ensureLoaded();
        return super.getNumElements();
    }

    @Override
    public MetadataElement getElementAt(int index) {
        ensureLoaded();
        return super.getElementAt(index);
    }

    @Override
    public String[] getElementNames() {
        ensureLoaded();
        return super.getElementNames();
    }

    @Override
    public MetadataElement[] getElements() {
        ensureLoaded();
        return super.getElements();
    }

    @Override
    public MetadataElement getElement(String name) {
        ensureLoaded();
        return super.getElement(name);
    }

    @Override
    public boolean containsElement(String name) {
        ensureLoaded();
        return super.containsElement(name);
    }

    @Override
    public int getElementIndex(MetadataElement element) {
        ensureLoaded();
        return super.getElementIndex(element);
    }

    @Override
    public int getNumAttributes() {
        ensureLoaded();
        return super.getNumAttributes();
    }

    @Override
    public MetadataAttribute getAttributeAt(int index) {
        ensureLoaded();
        return super.getAttributeAt(index);
    }

    @Override
    public String[] getAttributeNames() {
        ensureLoaded();
        return super.getAttributeNames();
    }

    @Override
    public MetadataAttribute[] getAttributes() {
        ensureLoaded();
        return super.getAttributes();
    }

    @Override
    public MetadataAttribute getAttribute(String name) {
        ensureLoaded();
        return super.getAttribute(name);
    }

    @Override
    public boolean containsAttribute(String name) {
        ensureLoaded();
        return super.containsAttribute(name);
    }

    @Override
    public int getAttributeIndex(MetadataAttribute attribute) {
        ensureLoaded();
        return super.getAttributeIndex(attribute);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }

        ProductNode owner = getOwner();
        try {
            setOwner(null);
            MetadataElement element = provider.readElement(variablePath);
            if (element != null) {
                for (MetadataElement child : element.getElements()) {
                    addElement(child);
                }
                for (MetadataAttribute attribute : element.getAttributes()) {
                    addAttribute(attribute);
                }
            }
            loaded = true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            setOwner(owner);
        }
    }
}