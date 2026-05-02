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
     * 替换进程级的兜底 debug sink。
     *
     * <p>业务代码应优先使用 NeoLinkAPI 暴露的实例级 sink。这个静态 sink 只为向后兼容的
     * 底层工具和仍然直接使用 {@link Debugger} 的测试保留。</p>
     *
     * @param sink debug 消息和异常的接收器
     */
    public static void setSink(BiConsumer<String, Throwable> sink) {
        Debugger.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * 恢复旧调用方使用的默认控制台输出器。
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
