package eu.esa.snap.cimr.dddb;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;


public class CimrDDDB {


    private static final String DB_RESOURCE_PATH = "eu/esa/snap/cimr/dddb/";

    private final Map<String, CimrProductDescriptor> productDescriptors = new HashMap<>();
    private final Map<String, CimrBandDefinition[]> bandDefinitions = new HashMap<>();
    private final Map<String, CimrVariableFamily[]> descriptorFiles = new HashMap<>();


    public static CimrDDDB getInstance() {
        return InstanceHolder.INSTANCE;
    }

    public CimrProductDescriptor getProductDescriptor(String productType, String formatVersion) throws IOException {
        String resourceName = productResourceName(productType, formatVersion);
        URL resourceUrl = getResourceUrl(resourceName);
        if (resourceUrl == null && hasText(formatVersion)) {
            resourceName = productResourceName(productType, null);
            resourceUrl = getResourceUrl(resourceName);
        }
        if (resourceUrl == null) {
            throw new IOException("Invalid CIMR DDDB resource: " + resourceName);
        }
        final URL finalResourceUrl = resourceUrl;
        final String version = versionFromResourceName(resourceName);
        return productDescriptors.computeIfAbsent(resourceName, k -> {
            CimrProductDescriptor descriptor = read(finalResourceUrl, CimrProductDescriptor.class);
            descriptor.setVersion(version);
            return descriptor;
        });
    }

    public CimrBandDefinition[] getBandDefinitions(CimrProductDescriptor productDescriptor) throws IOException {
        final String resourcePath = versionedResourcePath(productDescriptor, productDescriptor.getBandsFile());
        URL resourceUrl = getResourceUrl(resourcePath);
        if (resourceUrl == null) {
            throw new IOException("Invalid CIMR DDDB band resource: " + resourcePath);
        }
        return bandDefinitions.computeIfAbsent(resourcePath, k -> read(resourceUrl, CimrBandDefinition[].class));
    }

    public CimrVariableFamily[] getDescriptorFile(CimrProductDescriptor productDescriptor, String resourceName) throws IOException {
        final String resourcePath = versionedResourcePath(productDescriptor, resourceName);
        URL resourceUrl = getResourceUrl(resourcePath);
        if (resourceUrl == null) {
            throw new IOException("Invalid CIMR DDDB descriptor resource: " + resourcePath);
        }
        return descriptorFiles.computeIfAbsent(resourcePath, k -> read(resourceUrl, CimrVariableFamily[].class));
    }

    private static String productResourceName(String productType, String formatVersion) {
        if (!hasText(formatVersion)) {
            return productType + "/default/product.json";
        }
        return productType + "/" + formatVersion.trim() + "/product.json";
    }

    private static String versionedResourcePath(CimrProductDescriptor productDescriptor, String resourceName) {
        return productDescriptor.getProductType() + "/" + productDescriptor.getVersion() + "/" + resourceName;
    }

    private static String versionFromResourceName(String resourceName) {
        String[] parts = resourceName.split("/");
        return parts.length >= 2 ? parts[1] : "";
    }

    private URL getResourceUrl(String resourceName) {
        return CimrDDDB.class.getClassLoader().getResource(DB_RESOURCE_PATH + resourceName);
    }

    private static <T> T read(URL resourceUrl, Class<T> type) {
        try (InputStream inputStream = resourceUrl.openStream()) {
            return ObjectMapperHolder.OBJECT_MAPPER.readValue(inputStream, type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read CIMR DDDB resource: " + resourceUrl, e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static class InstanceHolder {
        private static final CimrDDDB INSTANCE = new CimrDDDB();
    }

    private static class ObjectMapperHolder {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    }
}
