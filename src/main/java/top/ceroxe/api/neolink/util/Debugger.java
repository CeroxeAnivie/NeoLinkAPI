package top.ceroxe.api.neolink.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.function.BiConsumer;

public final class Debugger {
    private static volatile boolean enabled = Boolean.getBoolean("neolink.debug");
    private static volatile BiConsumer<String, Throwable> sink = Debugger::writeToConsole;

    private Debugger() {
    }

    public static void setEnabled(boolean enabled) {
        Debugger.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Replaces the process-wide fallback debug sink.
     *
     * <p>Application code should prefer instance-level sinks exposed by
     * NeoLinkAPI. This static sink remains for backward-compatible low-level
     * helpers and tests that still use {@link Debugger} directly.</p>
     *
     * @param sink receiver for debug messages and exceptions
     */
    public static void setSink(BiConsumer<String, Throwable> sink) {
        Debugger.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Restores the default console writer used by older callers.
     */
    public static void resetSink() {
        Debugger.sink = Debugger::writeToConsole;
    }

    public static void debugOperation(boolean enabled, Exception e) {
        if (!enabled || e == null) {
            return;
        }
        emit(null, e);
    }

    public static void debugOperation(Exception e) {
        debugOperation(enabled, e);
    }

    public static void debugOperation(boolean enabled, String infoMsg) {
        if (enabled) {
            emit(infoMsg, null);
        }
    }

    public static void debugOperation(String infoMsg) {
        debugOperation(enabled, infoMsg);
    }

    private static void emit(String message, Throwable cause) {
        try {
            sink.accept(message, cause);
        } catch (RuntimeException ignored) {
            // Debug logging must never affect the tunnel or the host process.
        }
    }

    private static void writeToConsole(String message, Throwable cause) {
        if (message != null) {
            System.out.println("[NeoLinkAPI DEBUG] " + message);
        }
        if (cause != null) {
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            System.err.println("[NeoLinkAPI DEBUG] " + sw);
        }
    }
}
