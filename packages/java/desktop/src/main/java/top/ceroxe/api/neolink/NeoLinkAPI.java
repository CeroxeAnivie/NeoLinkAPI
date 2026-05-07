package top.ceroxe.api.neolink;

import top.ceroxe.api.OshiUtils;
import top.ceroxe.api.neolink.util.Debugger;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Desktop JVM entry point for NeoLinkAPI.
 *
 * <p>The transport lifecycle lives in {@link NeoLinkAPIBase}; this public class keeps the
 * stable desktop API surface and supplies JVM-specific runtime policy.</p>
 */
public final class NeoLinkAPI extends NeoLinkAPIBase {
    private static final String WINDOWS_UPDATE_CLIENT_TYPE = "exe";
    private static final String DEFAULT_UPDATE_CLIENT_TYPE = "jar";

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
        if (message != null) {
            Debugger.debugOperation(true, message);
        }
        if (cause instanceof Exception exception) {
            Debugger.debugOperation(true, exception);
        }
    }

    @Override
    protected NeoLinkAPI self() {
        return this;
    }

    @Override
    protected String platformUpdateClientType() {
        return OshiUtils.isWindows() ? WINDOWS_UPDATE_CLIENT_TYPE : DEFAULT_UPDATE_CLIENT_TYPE;
    }

    @Override
    protected ExecutorService createWorkerExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
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
