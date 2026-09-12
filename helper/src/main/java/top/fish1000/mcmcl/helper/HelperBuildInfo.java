package top.fish1000.mcmcl.helper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build and wire-protocol identity exposed by the helper handshake. */
public final class HelperBuildInfo {
    public static final int PROTOCOL_VERSION = 1;

    private static final String RESOURCE = "/META-INF/mcmcl-helper.properties";
    private static final Properties PROPERTIES = loadProperties();

    private HelperBuildInfo() {
    }

    public static String helperVersion() {
        return PROPERTIES.getProperty("helperVersion", "development");
    }

    public static String hmclCommit() {
        return PROPERTIES.getProperty("hmclCommit", "none");
    }

    public static boolean hmclProfile() {
        return Boolean.parseBoolean(PROPERTIES.getProperty("hmclProfile", "false"));
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = HelperBuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
            // Development classpaths may not have run processResources yet.
        }
        return properties;
    }
}
