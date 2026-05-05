package top.ceroxe.api.neolink;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class VersionInfo {
    public static final String VERSION = getApiVersion();
    public static final String AUTHOR = "Ceroxe";

    private VersionInfo() {
    }

    private static String getApiVersion() {
        Properties props = new Properties();
        try (InputStream is = VersionInfo.class.getClassLoader().getResourceAsStream("api.properties")) {
            if (is == null) {
                return "Dev-Build";
            }
            props.load(is);
        } catch (IOException e) {
            return "Unknown";
        }

        String version = props.getProperty("api.version");
        if (version == null || version.isBlank() || version.contains("${")) {
            return "Dev-Build";
        }
        return version.trim();
    }
}
