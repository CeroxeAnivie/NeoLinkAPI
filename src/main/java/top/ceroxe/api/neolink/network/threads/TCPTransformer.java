package top.ceroxe.api.neolink.network.threads;

import top.ceroxe.api.net.SecureSocket;
import top.ceroxe.api.neolink.util.Debugger;

import java.net.Socket;
import java.util.Objects;
import java.util.function.BiConsumer;

import static top.ceroxe.api.neolink.network.InternetOperator.*;

/**
 * TCP 数据转发器
 *
 * 核心职责：
 * 1. 在本地服务和 Neo 服务器之间双向转发 TCP 数据
 * 2. 支持 Proxy Protocol v2 的剥离或透传
 * 3. 通过复用实例缓冲区减少 GC 压力
 *
 * 设计特点：
 * - 双向转发：支持 Neo 到本地、本地到 Neo 两种模式
 * - 缓冲区复用：每个实例使用独立缓冲区，避免频繁分配内存
 * - Proxy Protocol v2 支持：可选剥离或透传真实客户端 IP
 * - 优雅关闭：支持中断信号，确保资源正确释放
 *
 * 性能优化：
 * - 使用 65535 字节缓冲区，充分利用网络带宽
 * - 缓冲区实例化后复用，减少 GC 压力
 *
 * @author NeoProxy Team
 * @since 5.0.0
 */
public class TCPTransformer implements Runnable {
    public static final int MODE_NEO_TO_LOCAL = 0;
    public static final int MODE_LOCAL_TO_NEO = 1;
    // Proxy Protocol v2 的 12 字节固定签名
    private static final byte[] PPV2_SIG = new byte[]{
            (byte) 0x0D, (byte) 0x0A, (byte) 0x0D, (byte) 0x0A,
            (byte) 0x00, (byte) 0x0D, (byte) 0x0A, (byte) 0x51,
            (byte) 0x55, (byte) 0x49, (byte) 0x54, (byte) 0x0A
    };
    private static final int PPV2_MIN_HEADER_LENGTH = 16;
    public static int BUFFER_LENGTH = 65535; // 可以保持为静态常量
    private final Socket plainSocket;
    private final SecureSocket secureSocket;
    private final int mode;
    private final boolean enableProxyProtocol;
    private final boolean debugEnabled;
    private final BiConsumer<String, Throwable> debugSink;

    // 每条转发链路独占缓冲区，避免多连接并发时共享数组导致数据串扰。
    private final byte[] buffer = new byte[BUFFER_LENGTH];

    /**
     * 构造函数：用于从 Neo 服务器接收数据并转发到本地服务。
     *
     * @param enableProxyProtocol 是否允许透传 Proxy Protocol 头
     */
    public TCPTransformer(SecureSocket secureSender, Socket localReceiver, boolean enableProxyProtocol) {
        this(secureSender, localReceiver, enableProxyProtocol, Debugger.isEnabled());
    }

    public TCPTransformer(SecureSocket secureSender, Socket localReceiver, boolean enableProxyProtocol, boolean debugEnabled) {
        this(secureSender, localReceiver, enableProxyProtocol, debugEnabled, TCPTransformer::defaultDebugSink);
    }

    public TCPTransformer(
            SecureSocket secureSender,
            Socket localReceiver,
            boolean enableProxyProtocol,
            boolean debugEnabled,
            BiConsumer<String, Throwable> debugSink
    ) {
        this.secureSocket = secureSender;
        this.plainSocket = localReceiver;
        this.mode = MODE_NEO_TO_LOCAL;
        this.enableProxyProtocol = enableProxyProtocol;
        this.debugEnabled = debugEnabled;
        this.debugSink = Objects.requireNonNull(debugSink, "debugSink");
    }

    /**
     * 构造函数：用于从本地服务接收数据并转发到 Neo 服务器。
     *
     * @param enableProxyProtocol 此方向通常不使用，可传 false
     */
    public TCPTransformer(Socket localSender, SecureSocket secureReceiver, boolean enableProxyProtocol) {
        this(localSender, secureReceiver, enableProxyProtocol, Debugger.isEnabled());
    }

    public TCPTransformer(Socket localSender, SecureSocket secureReceiver, boolean enableProxyProtocol, boolean debugEnabled) {
        this(localSender, secureReceiver, enableProxyProtocol, debugEnabled, TCPTransformer::defaultDebugSink);
    }

    public TCPTransformer(
            Socket localSender,
            SecureSocket secureReceiver,
            boolean enableProxyProtocol,
            boolean debugEnabled,
            BiConsumer<String, Throwable> debugSink
    ) {
        this.plainSocket = localSender;
        this.secureSocket = secureReceiver;
        this.mode = MODE_LOCAL_TO_NEO;
        this.enableProxyProtocol = enableProxyProtocol;
        this.debugEnabled = debugEnabled;
        this.debugSink = Objects.requireNonNull(debugSink, "debugSink");
    }

    /**
     * 将本地数据转发到 Neo 服务器 (Local -> Neo)
     */
    private void transferDataToNeoServer() {
        // 修改：直接获取 InputStream，不要包裹 BufferedInputStream
        try (var inputFromLocal = plainSocket.getInputStream()) {
            int bytesRead;
            // 直接从 Socket 读入 64KB 缓冲区，减少额外拷贝，同时保持每个连接的数据隔离。
            while ((bytesRead = inputFromLocal.read(buffer)) != -1) {
                debug("Forwarding TCP bytes from local service to NeoProxyServer. bytes=" + bytesRead);
                secureSocket.sendBytes(buffer, 0, bytesRead);
            }
            debug("Local TCP stream reached EOF. Sending NeoProxyServer EOF frame.");
            secureSocket.sendBytes(null); // 发送结束信号
            shutdownInput(plainSocket);
        } catch (Exception e) {
            debug(e);
            shutdownOutput(secureSocket);
            shutdownInput(plainSocket);
        }
    }

    /**
     * 将 Neo 服务器数据转发到本地 (Neo -> Local)
     * 【核心逻辑】在此处检测并处理 Proxy Protocol 头
     */
    private void transferDataToLocalServer() {
        // 修改：直接获取 OutputStream，不要包裹 BufferedOutputStream
        try (var outputToLocal = plainSocket.getOutputStream()) {
            byte[] data;
            boolean isFirstPacket = true;

            while ((data = secureSocket.receiveBytes()) != null) {
                if (data.length == 0) continue;
                debug("Received TCP frame from NeoProxyServer. bytes=" + data.length);

                if (isFirstPacket) {
                    isFirstPacket = false;
                    // 检测是否是 Proxy Protocol v2 头
                    if (isProxyProtocolV2Signature(data)) {
                        if (this.enableProxyProtocol) {
                            // 配置为开启：透传给本地后端
                            debug("Proxy Protocol v2 header detected and passed through to local service.");
                            outputToLocal.write(data);
                        } else {
                            // 配置为关闭：只剥离 PPv2 头，保留同一帧中已经携带的真实业务数据。
                            int headerLength = proxyProtocolV2HeaderLength(data);
                            debug("Proxy Protocol v2 header detected and stripped. headerBytes=" + headerLength);
                            if (data.length > headerLength) {
                                outputToLocal.write(data, headerLength, data.length - headerLength);
                            }
                            continue;
                        }
                    } else {
                        // 不是 PP 头，正常写入
                        outputToLocal.write(data);
                    }
                } else {
                    // 后续数据正常写入
                    outputToLocal.write(data);
                }

                // 移除 flush()，因为 SocketOutputStream 默认是直接发送的，且没有 Buffer 就不需要 flush
                // outputToLocal.flush();
            }
            debug("NeoProxyServer TCP stream reached EOF.");
            shutdownInput(secureSocket);
            shutdownOutput(plainSocket);
        } catch (Exception e) {
            debug(e);
            shutdownInput(secureSocket);
            shutdownOutput(plainSocket);
        }
    }

    /**
     * 检查数据包是否以 Proxy Protocol v2 签名开头
     */
    private boolean isProxyProtocolV2Signature(byte[] data) {
        if (data == null || data.length < PPV2_SIG.length) {
            return false;
        }
        for (int i = 0; i < PPV2_SIG.length; i++) {
            if (data[i] != PPV2_SIG[i]) {
                return false;
            }
        }
        return true;
    }

    private int proxyProtocolV2HeaderLength(byte[] data) {
        if (data.length < PPV2_MIN_HEADER_LENGTH) {
            return data.length;
        }
        int payloadLength = ((data[14] & 0xFF) << 8) | (data[15] & 0xFF);
        long headerLength = (long) PPV2_MIN_HEADER_LENGTH + payloadLength;
        if (headerLength > data.length) {
            return data.length;
        }
        return (int) headerLength;
    }

    @Override
    public void run() {
        try {
            debug("TCP transformer started. mode=" + (mode == MODE_NEO_TO_LOCAL ? "NEO_TO_LOCAL" : "LOCAL_TO_NEO")
                    + ", ppv2Enabled=" + enableProxyProtocol);
            if (mode == MODE_NEO_TO_LOCAL) {
                transferDataToLocalServer();
            } else {
                transferDataToNeoServer();
            }
        } catch (Exception e) {
            debug(e);
        } finally {
            // 无论正常结束还是异常结束，都确保关闭资源
            close(plainSocket, secureSocket);
            debug("TCP transformer closed sockets.");
        }
    }

    private void debug(String message) {
        if (!debugEnabled) {
            return;
        }
        emitDebug(message, null);
    }

    private void debug(Exception e) {
        if (!debugEnabled || e == null) {
            return;
        }
        emitDebug(null, e);
    }

    private void emitDebug(String message, Throwable cause) {
        try {
            debugSink.accept(message, cause);
        } catch (RuntimeException ignored) {
            // Debug callbacks are observational and must not disturb forwarding.
        }
    }

    private static void defaultDebugSink(String message, Throwable cause) {
        if (message != null) {
            Debugger.debugOperation(true, message);
        }
        if (cause instanceof Exception exception) {
            Debugger.debugOperation(true, exception);
        }
    }
}
