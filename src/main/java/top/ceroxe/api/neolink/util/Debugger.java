package top.ceroxe.api.neolink.util;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class Debugger {
    private static volatile boolean enabled = Boolean.getBoolean("neolink.debug");

    private Debugger() {
    }

    public static void setEnabled(boolean enabled) {
        Debugger.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void debugOperation(boolean enabled, Exception e) {
        if (!enabled || e == null) {
            return;
        }

        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        System.err.println("[NeoLinkAPI DEBUG] " + sw);
    }

    public static void debugOperation(Exception e) {
        debugOperation(enabled, e);
    }

    public static void debugOperation(boolean enabled, String infoMsg) {
        if (enabled) {
            System.out.println("[NeoLinkAPI DEBUG] " + infoMsg);
        }
    }

    public static void debugOperation(String infoMsg) {
        debugOperation(enabled, infoMsg);
    }
}
