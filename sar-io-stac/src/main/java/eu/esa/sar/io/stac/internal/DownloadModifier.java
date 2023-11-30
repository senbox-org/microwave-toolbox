package eu.esa.sar.io.stac.internal;

public interface DownloadModifier {
    String signURL(String input);
}
