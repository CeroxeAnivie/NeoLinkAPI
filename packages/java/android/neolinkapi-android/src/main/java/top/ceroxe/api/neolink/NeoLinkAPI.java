package top.ceroxe.api.neolink;

import android.os.Looper;
import android.util.Log;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android entry point for NeoLinkAPI.
 *
 * <p>The shared tunnel lifecycle lives in {@link NeoLinkAPIBase}; this class only keeps
 * Android-specific execution and logging policy at the API boundary.</p>
 */
public final class NeoLinkAPI extends NeoLinkAPIBase {
    private static final String DEFAULT_ANDROID_LOG_TAG = "NeoLinkAPI";
    private static final String DEFAULT_UPDATE_CLIENT_TYPE = "jar";
    private static final AtomicInteger WORKER_THREAD_ID = new AtomicInteger(1);

    public NeoLinkAPI(NeoLinkCfg cfg) {
        super(cfg, NeoLinkAPI::defaultDebugSink);
    }

    public static String version() {
        return NeoLinkAPIBase.version();
    }

    static String parseTunAddrMessage(String message) {
        return NeoLinkAPIBase.parseTunAddrMessage(message);
    }

    private static void defaultDebugSink(String message, Throwable cause) {
        logDebugMessage(DEFAULT_ANDROID_LOG_TAG, message, cause);
    }

    private static void logDebugMessage(String tag, String message, Throwable cause) {
        if (message != null && cause != null) {
            Log.d(tag, message, cause);
            return;
        }
        if (message != null) {
            Log.d(tag, message);
            return;
        }
        if (cause != null) {
            Log.d(tag, "Debug exception.", cause);
        }
    }

    @Override
    protected NeoLinkAPI self() {
        return this;
    }

    @Override
    protected String platformUpdateClientType() {
        return DEFAULT_UPDATE_CLIENT_TYPE;
    }

    @Override
    protected ExecutorService createWorkerExecutor() {
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "neolink-android-worker-" + WORKER_THREAD_ID.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(threadFactory);
    }

    @Override
    protected void requireStartThreadAllowed() {
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper == null) {
            return;
        }
        if (Thread.currentThread() == mainLooper.getThread()) {
            throw new IllegalStateException(
                    "NeoLinkAPI.start() must not run on the Android main thread. "
                            + "Use a background thread, coroutine dispatcher, or foreground service."
            );
        }
    }

    public NeoLinkAPI bindAndroidLog() {
        return bindAndroidLog(DEFAULT_ANDROID_LOG_TAG);
    }

    public NeoLinkAPI bindAndroidLog(String tag) {
        String normalizedTag = Objects.requireNonNull(tag, "tag").trim();
        if (normalizedTag.isEmpty()) {
            throw new IllegalArgumentException("tag must not be blank.");
        }

        return setOnStateChanged(state -> Log.i(normalizedTag, "state = " + state))
                .setOnServerMessage(message -> Log.i(normalizedTag, "server = " + message))
                .setOnError((message, cause) -> {
                    if (message != null && cause != null) {
                        Log.e(normalizedTag, message, cause);
                    } else if (message != null) {
                        Log.e(normalizedTag, message);
                    } else if (cause != null) {
                        Log.e(normalizedTag, "NeoLinkAPI runtime error.", cause);
                    }
                })
                .setDebugSink((message, cause) -> logDebugMessage(normalizedTag, message, cause));
    }

    /**
     * 转发连接使用的传输协议类型。
     */
    public enum TransportProtocol {
        TCP,
        UDP
    }

    /**
     * 转发流量的方向。
     *
     * <p>方向以 NeoLink 客户端为观察点：{@link #NEO_TO_LOCAL} 表示来自 NeoProxyServer
     * 并已交付给本地下游服务的业务负载，{@link #LOCAL_TO_NEO} 表示来自本地下游服务
     * 并已交付给 NeoProxyServer 的业务负载。</p>
     */
    public enum TrafficDirection {
        NEO_TO_LOCAL,
        LOCAL_TO_NEO
    }

    /**
     * 接收转发连接事件，协议类型由 NeoLinkAPI 解析后传入。
     */
    @FunctionalInterface
    public interface ConnectionEventHandler {
        void accept(TransportProtocol protocol, InetSocketAddress source, InetSocketAddress target);
    }

    /**
     * 接收已成功转发的业务流量事件。
     */
    @FunctionalInterface
    public interface TrafficEventHandler {
        void accept(TransportProtocol protocol, TrafficDirection direction, long bytes);
    }
}
