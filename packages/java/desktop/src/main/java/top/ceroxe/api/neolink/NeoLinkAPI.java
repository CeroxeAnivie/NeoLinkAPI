package top.ceroxe.api.neolink;

import top.ceroxe.api.OshiUtils;
import top.ceroxe.api.neolink.exception.*;
import top.ceroxe.api.neolink.network.InternetOperator;
import top.ceroxe.api.neolink.network.ProxyOperator;
import top.ceroxe.api.neolink.network.threads.CheckAliveThread;
import top.ceroxe.api.neolink.network.threads.TCPTransformer;
import top.ceroxe.api.neolink.network.threads.UDPTransformer;
import top.ceroxe.api.neolink.util.Debugger;
import top.ceroxe.api.net.SecureSocket;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * NeoLinkAPI 的唯一隧道控制入口。
 *
 * <p>该类把控制连接、心跳检测、服务端指令监听和 TCP/UDP 转发连接统一收束到一个
 * 可关闭的实例中。调用方只需要管理自身业务生命周期；API 内部负责在异常路径上释放
 * socket、线程和活动转发连接。</p>
 */
public final class NeoLinkAPI implements AutoCloseable {
    private static final String UNKNOWN_HOST = "unknown";
    private static final String WINDOWS_UPDATE_CLIENT_TYPE = "exe";
    private static final String DEFAULT_UPDATE_CLIENT_TYPE = "jar";
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 1_000;
    private static final int UPDATE_URL_TIMEOUT_MILLIS = 15_000;
    private static final Runnable NOOP = () -> {
    };
    private static final Consumer<NeoLinkState> NOOP_STATE_HANDLER = state -> {
    };
    private static final Consumer<String> NOOP_MESSAGE_HANDLER = message -> {
    };
    private static final ConnectionEventHandler NOOP_CONNECTION_EVENT_HANDLER = (protocol, source, target) -> {
    };
    private static final BiConsumer<String, Throwable> NOOP_ERROR_HANDLER = (message, cause) -> {
    };
    private static final Function<String, Boolean> REQUEST_UNSUPPORTED_VERSION_UPDATE = response -> true;
    private static final long PROTOCOL_SWITCH_GRACE_MILLIS = 3_000L;

    private final NeoLinkCfg cfg;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicLong lifecycleGeneration = new AtomicLong(0);
    private final Set<Closeable> activeConnections = ConcurrentHashMap.newKeySet();

    private volatile NeoLinkCfg runtimeCfg;
    private volatile ProxyOperator proxyOperator;
    private volatile ExecutorService workerExecutor;
    private volatile CheckAliveThread checkAliveThread;
    private volatile CompletableFuture<Void> startupFuture;
    private volatile SecureSocket hookSocket;
    private volatile Socket connectingSocket;
    private volatile Future<?> supervisorFuture;
    private volatile String updateUrl;
    private volatile String tunAddr;
    private volatile CompletableFuture<String> tunAddrFuture = new CompletableFuture<>();
    private volatile long lastReceivedTime = System.currentTimeMillis();
    private volatile int runtimeConnectToNpsTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
    private volatile NeoLinkState state = NeoLinkState.STOPPED;
    private volatile ProtocolFlags pendingProtocolSwitchRollback;
    private volatile ProtocolSwitch pendingProtocolSwitch;
    private volatile String lastServerTerminalMessage;

    private volatile ConnectionEventHandler onConnect = NOOP_CONNECTION_EVENT_HANDLER;
    private volatile ConnectionEventHandler onDisconnect = NOOP_CONNECTION_EVENT_HANDLER;
    private volatile Runnable onConnectNeoFailure = NOOP;
    private volatile Runnable onConnectLocalFailure = NOOP;
    private volatile Consumer<NeoLinkState> onStateChanged = NOOP_STATE_HANDLER;
    private volatile BiConsumer<String, Throwable> onError = NOOP_ERROR_HANDLER;
    private volatile Consumer<String> onServerMessage = NOOP_MESSAGE_HANDLER;
    private volatile Function<String, Boolean> unsupportedVersionDecision = REQUEST_UNSUPPORTED_VERSION_UPDATE;
    private volatile BiConsumer<String, Throwable> debugSink = NeoLinkAPI::defaultDebugSink;

    /**
     * 绑定一个不可为空的配置对象。
     *
     * <p>{@link #start()} 会复制该配置，之后运行中的隧道不再读取原配置对象，
     * 这样调用方在启动后继续修改 {@link NeoLinkCfg} 也不会产生半生效状态。</p>
     *
     * @param cfg 隧道配置
     */
    public NeoLinkAPI(NeoLinkCfg cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
    }

    /**
     * 返回当前 API 包版本。
     *
     * @return 从 {@code api.properties} 解析出的版本号
     */
    public static String version() {
        return VersionInfo.VERSION;
    }

    private static Exception classifyRuntimeTerminalFailure(String response) {
        return NeoLinkProtocolSupport.classifyRuntimeTerminalFailure(response);
    }

    private static String normalizeUpdateURL(String response) {
        return NeoLinkProtocolSupport.normalizeUpdateURL(response);
    }

    private static String updateClientType() {
        return OshiUtils.isWindows() ? WINDOWS_UPDATE_CLIENT_TYPE : DEFAULT_UPDATE_CLIENT_TYPE;
    }

    private static Exception classifyStartupHandshakeFailure(String response) {
        return NeoLinkProtocolSupport.classifyStartupHandshakeFailure(response);
    }

    private static boolean isSuccessfulHandshakeResponse(String response) {
        return NeoLinkProtocolSupport.isSuccessfulHandshakeResponse(response);
    }

    private static boolean isNoMorePortResponse(String response) {
        return NeoLinkProtocolSupport.isNoMorePortResponse(response);
    }

    static String parseTunAddrMessage(String message) {
        return NeoLinkProtocolSupport.parseTunAddrMessage(message);
    }

    private static IOException toIOException(String message, Exception cause) {
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(message, cause);
    }

    private static int requirePositive(int value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0.");
        }
        return value;
    }

    private static InetSocketAddress parseRemoteAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return InetSocketAddress.createUnresolved(UNKNOWN_HOST, 0);
        }

        String value = remoteAddress.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }

        if (value.startsWith("[")) {
            int closingBracket = value.indexOf(']');
            if (closingBracket > 1) {
                String host = value.substring(1, closingBracket);
                int port = parsePortAfter(value, closingBracket + 1);
                return InetSocketAddress.createUnresolved(host, port);
            }
        }

        int lastColon = value.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < value.length()) {
            String host = value.substring(0, lastColon);
            int port = parsePort(value.substring(lastColon + 1));
            if (port >= 0) {
                return InetSocketAddress.createUnresolved(host, port);
            }
        }
        return InetSocketAddress.createUnresolved(value, 0);
    }

    private static int parsePortAfter(String value, int index) {
        if (index >= value.length() || value.charAt(index) != ':' || index + 1 >= value.length()) {
            return 0;
        }
        int port = parsePort(value.substring(index + 1));
        return port >= 0 ? port : 0;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
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

    private static String maskClientInfo(String clientInfo) {
        String[] parts = clientInfo.split(";", -1);
        if (parts.length < 4) {
            return clientInfo;
        }
        parts[2] = maskSecret(parts[2]);
        return String.join(";", parts);
    }

    private static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        if (secret.length() <= 4) {
            return "****";
        }
        return secret.substring(0, 2) + "****" + secret.substring(secret.length() - 2);
    }

    /**
     * 建立控制连接、完成握手并阻塞运行到隧道停止。
     *
     * <p>该方法会先阻塞到握手成功或失败；握手成功后继续阻塞，直到其他线程调用
     * {@link #close()}、服务端关闭连接，或运行期遇到终止性错误。TCP/UDP、PPv2、
     * 语言和 debug 等协议相关配置必须在调用本方法之前写入 {@link NeoLinkCfg}，
     * 因为它们会参与握手或转发线程初始化。</p>
     *
     * @throws IOException                 控制连接、传输连接或本地下游连接发生 I/O 错误
     * @throws UnsupportedVersionException 服务端拒绝当前 API 版本
     * @throws NoSuchKeyException          服务端拒绝访问密钥
     * @throws NoMoreNetworkFlowException  服务端通知剩余流量耗尽
     */
    public void start()
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        start(DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    /**
     * 使用自定义 NPS 连接超时时间建立隧道，并阻塞运行到隧道停止。
     *
     * <p>该超时只用于连接 NeoProxyServer 的控制端口和传输端口；本地下游服务连接仍使用
     * 默认超时，避免“远端 NPS 连接策略”意外改变宿主应用的本地服务探测语义。</p>
     *
     * @param connectToNpsTimeoutMillis 连接 NeoProxyServer 控制端口和传输端口的超时时间，必须大于 0
     * @throws IllegalArgumentException    超时时间小于 1 毫秒时抛出
     * @throws IOException                 控制连接、传输连接或本地下游连接发生 I/O 错误
     * @throws UnsupportedVersionException 服务端拒绝当前 API 版本
     * @throws NoSuchKeyException          服务端拒绝访问密钥
     * @throws NoMoreNetworkFlowException  服务端通知剩余流量耗尽
     */
    public void start(int connectToNpsTimeoutMillis)
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        int validatedConnectToNpsTimeoutMillis = requirePositive(
                connectToNpsTimeoutMillis,
                "connectToNpsTimeoutMillis"
        );
        boolean notifyStarting;
        Future<?> activeSupervisorFuture;
        synchronized (lifecycleLock) {
            if (isActive()) {
                debug("start() ignored because this NeoLinkAPI instance is already active.");
                return;
            }

            long generation = lifecycleGeneration.incrementAndGet();
            resetTunAddrState();
            runtimeCfg = cfg.copy();
            runtimeCfg.requireStartReady();
            ProxyOperator startupProxyOperator = new ProxyOperator(
                    runtimeCfg.getRemoteDomainName(),
                    runtimeCfg.getLocalDomainName(),
                    runtimeCfg.getProxyIPToNeoServer(),
                    runtimeCfg.getProxyIPToLocalServer()
            );
            updateUrl = null;
            lastServerTerminalMessage = null;
            pendingProtocolSwitch = null;
            pendingProtocolSwitchRollback = null;
            runtimeConnectToNpsTimeoutMillis = validatedConnectToNpsTimeoutMillis;
            notifyStarting = moveStateTo(NeoLinkState.STARTING);
            debug("Starting NeoLinkAPI tunnel. " + describeRuntimeConfig());
            proxyOperator = startupProxyOperator;
            workerExecutor = Executors.newVirtualThreadPerTaskExecutor();
            startupFuture = new CompletableFuture<>();
            checkAliveThread = new CheckAliveThread(
                    () -> hookSocket,
                    () -> lastReceivedTime,
                    runtimeCfg.getHeartBeatPacketDelay(),
                    this::emitError,
                    isDebugEnabled(),
                    this::emitDebug
            );
            closeRequested.set(false);
            running.set(true);
            supervisorFuture = workerExecutor.submit(() -> {
                runCore(generation);
                return null;
            });
            activeSupervisorFuture = supervisorFuture;
        }
        if (notifyStarting) {
            emitStateChanged(NeoLinkState.STARTING);
        }

        try {
            awaitStartup();
            awaitRuntime(activeSupervisorFuture);
        } catch (IOException | UnsupportedVersionException | NoSuchKeyException | NoMoreNetworkFlowException e) {
            close();
            throw e;
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /**
     * 查询隧道是否处于运行状态。
     *
     * @return {@code true} 表示控制连接仍处于活动状态
     */
    public boolean isActive() {
        return running.get();
    }

    /**
     * 阻塞等待 NeoProxyServer 明确下发的远程连接地址。
     *
     * <p>NPS 在隧道注册成功后会通过控制连接发送完整的客户端可见连接地址，例如
     * {@code p.ceroxe.fun:45678}。API 必须以该地址为准，不能让调用方根据远端域名和
     * 下发端口自行拼接，否则会破坏服务端对公网域名、反代入口或未来 URL 形态的控制权。
     * 如果在 {@link #start()} 前调用，本方法会一直阻塞到当前对象实际收到地址；一旦
     * 收到过地址，后续调用立即返回最近一次地址。</p>
     *
     * @return NeoProxyServer 下发的远程连接地址
     */
    public String getTunAddr() {
        String currentTunAddr = tunAddr;
        if (currentTunAddr != null) {
            return currentTunAddr;
        }

        boolean interrupted = false;
        while (true) {
            CompletableFuture<String> future = tunAddrFuture;
            try {
                String resolvedTunAddr = future.get();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return resolvedTunAddr;
            } catch (InterruptedException e) {
                interrupted = true;
            } catch (CancellationException e) {
                if (future != tunAddrFuture) {
                    continue;
                }
            } catch (ExecutionException e) {
                if (future != tunAddrFuture) {
                    continue;
                }
                throw new IllegalStateException("Unexpected tunnel address resolution failure.", e.getCause());
            }
        }
    }

    /**
     * 返回当前控制链路使用的安全 socket。
     *
     * <p>该引用只用于观测或与外部已有协议集成；调用方不应主动关闭它，否则会中断
     * NeoLinkAPI 的心跳、服务端指令监听和生命周期清理。</p>
     *
     * @return 控制链路未建立或已经释放时返回 {@code null}
     */
    public SecureSocket getHookSocket() {
        return hookSocket;
    }

    /**
     * 返回服务端在版本不兼容流程中下发的客户端更新地址。
     *
     * <p>只有握手阶段触发 {@link UnsupportedVersionException} 且 NeoProxyServer 实际返回
     * 非空 URL 时才会有值。普通启动成功、服务端没有返回 URL 或返回 {@code false} 时均为
     * {@code null}。</p>
     *
     * @return 最近一次版本不兼容握手获得的更新地址，没有可用地址时返回 {@code null}
     */
    public String getUpdateURL() {
        return updateUrl;
    }

    /**
     * 返回当前生命周期状态。
     *
     * @return 最近一次状态机转移后的状态
     */
    public NeoLinkState getState() {
        return state;
    }

    /**
     * 设置生命周期状态变化回调。
     *
     * <p>回调异常会被 API 捕获并写入 debug sink，不会中断隧道线程。</p>
     *
     * @param onStateChanged 状态变化回调
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnStateChanged(Consumer<NeoLinkState> onStateChanged) {
        this.onStateChanged = Objects.requireNonNull(onStateChanged, "onStateChanged");
        return this;
    }

    /**
     * 设置运行期错误回调。
     *
     * <p>该通道用于业务可见错误，例如心跳失败、服务端关闭、流量耗尽或运行期 I/O
     * 异常。调试日志不会混入该通道。</p>
     *
     * @param onError 参数为错误摘要和原始异常
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnError(BiConsumer<String, Throwable> onError) {
        this.onError = Objects.requireNonNull(onError, "onError");
        return this;
    }

    /**
     * 设置服务端普通消息回调。
     *
     * <p>非 {@code :>} 控制命令的服务端文本会通过该回调交给宿主应用展示或记录。
     * API 不在这里耦合 GUI、CLI 或本地化文案。</p>
     *
     * @param onServerMessage 服务端消息回调
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnServerMessage(Consumer<String> onServerMessage) {
        this.onServerMessage = Objects.requireNonNull(onServerMessage, "onServerMessage");
        return this;
    }

    /**
     * 设置不支持当前版本时是否向服务端声明“调用方会尝试更新”。
     *
     * <p>API 不执行下载或替换文件。该决策函数只负责返回要发给服务端的布尔值：
     * {@code true} 表示宿主应用需要服务端下发更新 URL 并将自行处理更新，{@code false} 表示不请求更新 URL。
     * 默认值为 {@code true}，以便 {@link #getUpdateURL()} 能在版本不兼容异常后暴露服务端返回的地址。</p>
     *
     * @param unsupportedVersionDecision 输入为服务端原始响应，返回是否请求更新
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setUnsupportedVersionDecision(Function<String, Boolean> unsupportedVersionDecision) {
        this.unsupportedVersionDecision = Objects.requireNonNull(
                unsupportedVersionDecision,
                "unsupportedVersionDecision"
        );
        return this;
    }

    /**
     * 设置实例级调试事件接收器。
     *
     * <p>调试 sink 只接收诊断细节。业务错误请使用 {@link #setOnError(BiConsumer)}，
     * 生命周期请使用 {@link #setOnStateChanged(Consumer)}。回调异常会被隔离。</p>
     *
     * @param debugSink 参数为调试消息和异常；两者至少一个非空
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setDebugSink(BiConsumer<String, Throwable> debugSink) {
        this.debugSink = Objects.requireNonNull(debugSink, "debugSink");
        return this;
    }

    /**
     * 设置转发连接建立时触发的回调。
     *
     * @param onConnect 回调参数依次为协议、远端访问者地址和本地下游地址
     * @return 当前隧道实例，便于链式调用
     */
    public NeoLinkAPI setOnConnect(ConnectionEventHandler onConnect) {
        this.onConnect = Objects.requireNonNull(onConnect, "onConnect");
        return this;
    }

    /**
     * 设置转发连接关闭时触发的回调。
     *
     * @param onDisconnect 回调参数依次为协议、远端访问者地址和本地下游地址
     * @return 当前隧道实例，便于链式调用
     */
    public NeoLinkAPI setOnDisconnect(ConnectionEventHandler onDisconnect) {
        this.onDisconnect = Objects.requireNonNull(onDisconnect, "onDisconnect");
        return this;
    }

    public NeoLinkAPI setOnConnectNeoFailure(Runnable onConnectNeoFailure) {
        this.onConnectNeoFailure = Objects.requireNonNull(onConnectNeoFailure, "onConnectNeoFailure");
        return this;
    }

    /**
     * 设置连接本地下游服务失败时的回调。
     *
     * @param onConnectLocalFailure 本地下游连接失败时执行
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnConnectLocalFailure(Runnable onConnectLocalFailure) {
        this.onConnectLocalFailure = Objects.requireNonNull(onConnectLocalFailure, "onConnectLocalFailure");
        return this;
    }

    /**
     * 运行期切换 TCP/UDP 转发能力 (runtime protocol switch)。
     *
     * <p>这里直接发送 NeoProxyServer 原生控制载荷 (control payload)：{@code ""}、
     * {@code T}、{@code U} 或 {@code TU}。命令成功写入控制连接后，本地运行期闸门立即
     * 跟随更新；如果服务端随后用标准端口冲突文案拒绝，监听线程会把本地闸门回滚到发送前状态。</p>
     *
     * @param tcpEnabled 是否允许服务端继续派发 TCP 转发
     * @param udpEnabled 是否允许服务端继续派发 UDP 转发
     * @throws IOException 控制连接未运行、已关闭或发送控制载荷失败时抛出
     */
    public void updateRuntimeProtocolFlags(boolean tcpEnabled, boolean udpEnabled) throws IOException {
        ProtocolFlags requestedFlags = new ProtocolFlags(tcpEnabled, udpEnabled);
        SecureSocket activeHookSocket;
        synchronized (lifecycleLock) {
            activeHookSocket = requireActiveHookSocket();
            NeoLinkCfg activeCfg = requireRuntimeCfg();
            ProtocolFlags currentFlags = ProtocolFlags.from(activeCfg);
            if (currentFlags.equals(requestedFlags)) {
                debug("Ignored runtime protocol-switch request because the requested flags already match.");
                return;
            }
            pendingProtocolSwitchRollback = currentFlags;
            pendingProtocolSwitch = new ProtocolSwitch(
                    currentFlags,
                    requestedFlags,
                    System.currentTimeMillis() + PROTOCOL_SWITCH_GRACE_MILLIS
            );
            synchronized (activeHookSocket) {
                activeHookSocket.sendStr(requestedFlags.asProtocolFlags());
            }
            activeCfg.setTCPEnabled(tcpEnabled);
            activeCfg.setUDPEnabled(udpEnabled);
        }
        debug("Runtime protocol-switch command sent. flags=" + requestedFlags.asProtocolFlags());
    }

    private void runCore(long generation) throws Exception {
        try {
            connectToNeoServer();
            exchangeClientInfoWithServer();
            CheckAliveThread activeCheckAliveThread = checkAliveThread;
            if (activeCheckAliveThread != null) {
                activeCheckAliveThread.startThread();
            }
            transitionTo(NeoLinkState.RUNNING);
            completeStartup(generation, null);
            listenForServerCommands();
        } catch (UnsupportedVersionException | NoSuchKeyException | NoMoreNetworkFlowException e) {
            boolean runtimeFailure = isStartupComplete();
            completeStartup(generation, e);
            closeRequested.set(true);
            transitionTo(NeoLinkState.FAILED);
            emitError(e.getMessage(), e);
            if (runtimeFailure) {
                throw e;
            }
        } catch (IOException e) {
            boolean runtimeFailure = isStartupComplete();
            completeStartup(generation, e);
            if (running.get() && !closeRequested.get()) {
                transitionTo(NeoLinkState.FAILED);
                emitError("NeoLinkAPI 隧道异常停止。", e);
                debug(e);
                if (runtimeFailure) {
                    throw e;
                }
            }
        } catch (Exception e) {
            IOException exception = toIOException("NeoLinkAPI 隧道异常停止。", e);
            boolean runtimeFailure = isStartupComplete();
            completeStartup(generation, exception);
            if (running.get() && !closeRequested.get()) {
                transitionTo(NeoLinkState.FAILED);
                emitError(exception.getMessage(), exception);
                debug(exception);
                if (runtimeFailure) {
                    throw exception;
                }
            }
        } finally {
            cleanupLifecycle(generation);
        }
    }

    private void connectToNeoServer() throws IOException {
        int timeoutMillis = runtimeConnectToNpsTimeoutMillis;
        if (proxyOperator.hasProxy(ProxyOperator.TO_NEO)) {
            debug("Connecting to NeoProxyServer hook through per-instance proxy. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHookPort()
                    + ", timeoutMs=" + timeoutMillis);
            hookSocket = proxyOperator.getHandledSecureSocket(
                    ProxyOperator.TO_NEO,
                    runtimeCfg.getHookPort(),
                    timeoutMillis
            );
            registerConnection(hookSocket);
            return;
        }

        Socket socket = new Socket();
        registerConnection(socket);
        connectingSocket = socket;
        try {
            debug("Connecting to NeoProxyServer hook directly. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHookPort()
                    + ", timeoutMs=" + timeoutMillis);
            socket.connect(
                    new InetSocketAddress(runtimeCfg.getRemoteDomainName(), runtimeCfg.getHookPort()),
                    timeoutMillis
            );
            unregisterConnection(socket);
            hookSocket = new SecureSocket(socket);
            registerConnection(hookSocket);
        } catch (IOException e) {
            InternetOperator.close(socket);
            unregisterConnection(socket);
            throw e;
        } finally {
            connectingSocket = null;
        }
    }

    private void exchangeClientInfoWithServer()
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        String clientInfo = formatClientInfoString();
        debug("Sending client handshake. value=" + maskClientInfo(clientInfo));
        hookSocket.sendStr(clientInfo);
        String serverResponse = hookSocket.receiveStr();
        if (serverResponse == null) {
            throw new IOException("NeoProxyServer 在握手阶段关闭了连接。");
        }
        debug("Received server handshake response. value=" + serverResponse);

        Exception startupFailure = classifyStartupHandshakeFailure(serverResponse);
        if (startupFailure instanceof UnSupportHostVersionException unsupportedVersionException) {
            handleUnsupportedVersionResponse(serverResponse);
            throw unsupportedVersionException;
        }
        if (startupFailure instanceof NoMoreNetworkFlowException noMoreNetworkFlowException) {
            throw noMoreNetworkFlowException;
        }
        if (startupFailure instanceof NoSuchKeyException noSuchKeyException) {
            throw noSuchKeyException;
        }
        if (startupFailure instanceof IOException ioException) {
            throw ioException;
        }
        if (!isSuccessfulHandshakeResponse(serverResponse)) {
            throw new IOException("NeoProxyServer rejected the tunnel startup: " + serverResponse);
        }

        lastReceivedTime = System.currentTimeMillis();
    }

    private void handleUnsupportedVersionResponse(String serverResponse) {
        updateUrl = null;
        boolean requestUpdate = shouldRequestUnsupportedVersionUpdate(serverResponse);
        try {
            hookSocket.sendStr(Boolean.toString(requestUpdate));
            if (!requestUpdate) {
                return;
            }

            hookSocket.sendStr(updateClientType());
            updateUrl = normalizeUpdateURL(hookSocket.receiveStr(UPDATE_URL_TIMEOUT_MILLIS));
            debug("Unsupported-version update URL negotiated. available=" + (updateUrl != null));
        } catch (IOException e) {
            debug("Unable to finish unsupported-version update URL negotiation.");
            debug(e);
        }
    }

    String formatClientInfoString() {
        StringBuilder info = new StringBuilder()
                .append(runtimeCfg.getLanguage())
                .append(';')
                .append(runtimeCfg.getClientVersion())
                .append(';')
                .append(runtimeCfg.getKey())
                .append(';')
                .append(ProtocolFlags.from(runtimeCfg).asProtocolFlags());
        return info.toString();
    }

    private void listenForServerCommands() throws IOException, NoSuchKeyException, NoMoreNetworkFlowException {
        String message;
        while (running.get() && (message = hookSocket.receiveStr()) != null) {
            lastReceivedTime = System.currentTimeMillis();
            settlePendingProtocolSwitchIfExpired();
            debug("Received server message. value=" + message);
            if (message.startsWith(":>")) {
                handleServerCommand(message.substring(2));
            } else {
                rollbackRuntimeProtocolSwitchIfRejected(message);
                captureTerminalServerMessage(message);
                captureTunAddrIfPresent(message);
                emitServerMessage(message);
                debug("Ignored non-command server message.");
            }
        }
        throw new IOException("NeoProxyServer 连接已关闭。");
    }

    private void handleServerCommand(String command) throws IOException, NoSuchKeyException, NoMoreNetworkFlowException {
        String[] parts = command.split(";");
        switch (parts[0]) {
            case "sendSocketTCP" -> {
                if (isDispatchEnabled(TransportProtocol.TCP) && parts.length >= 3) {
                    debug("Scheduling TCP tunnel. socketId=" + parts[1] + ", remoteAddress=" + parts[2]);
                    submitWorker(() -> createNewTCPConnection(parts[1], parts[2]));
                } else {
                    debug("Ignored TCP tunnel command because TCP is disabled or command is malformed.");
                }
            }
            case "sendSocketUDP" -> {
                if (isDispatchEnabled(TransportProtocol.UDP) && parts.length >= 3) {
                    debug("Scheduling UDP tunnel. socketId=" + parts[1] + ", remoteAddress=" + parts[2]);
                    submitWorker(() -> createNewUDPConnection(parts[1], parts[2]));
                } else {
                    debug("Ignored UDP tunnel command because UDP is disabled or command is malformed.");
                }
            }
            case "exit" -> {
                Exception terminalException = classifyRuntimeTerminalFailure(lastServerTerminalMessage);
                closeActiveConnection();
                if (terminalException instanceof NoMoreNetworkFlowException noMoreNetworkFlowException) {
                    throw noMoreNetworkFlowException;
                }
                if (terminalException instanceof NoSuchKeyException noSuchKeyException) {
                    throw noSuchKeyException;
                }
                if (terminalException instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("NeoProxyServer requested tunnel shutdown.");
            }
            case "exitNoFlow" -> {
                closeRequested.set(true);
                running.set(false);
                NoMoreNetworkFlowException exception = new NoMoreNetworkFlowException();
                emitError(exception.getMessage(), exception);
                closeActiveConnection();
                throw exception;
            }
            default -> debug("Ignored unknown server command. value=" + parts[0]);
        }
    }

    private void createNewTCPConnection(String socketId, String remoteAddress) {
        Socket localServerSocket = null;
        SecureSocket neoTransferSocket = null;
        try {
            debug("Creating TCP tunnel. socketId=" + socketId + ", remoteAddress=" + remoteAddress);
            localServerSocket = openLocalSocket();
            neoTransferSocket = openTransferSocket();
            registerConnection(localServerSocket);
            registerConnection(neoTransferSocket);
            neoTransferSocket.sendStr("TCP" + ";" + socketId);

            emitConnectionEvent(onConnect, TransportProtocol.TCP, remoteAddress);

            TCPTransformer serverToLocalTask = new TCPTransformer(
                    neoTransferSocket,
                    localServerSocket,
                    runtimeCfg.isPPV2Enabled(),
                    isDebugEnabled(),
                    this::emitDebug
            );
            TCPTransformer localToServerTask = new TCPTransformer(
                    localServerSocket,
                    neoTransferSocket,
                    false,
                    isDebugEnabled(),
                    this::emitDebug
            );
            ExecutorService executor = requireWorkerExecutor();
            Future<?> first = executor.submit(serverToLocalTask);
            Future<?> second = executor.submit(localToServerTask);
            Socket trackedLocalSocket = localServerSocket;
            SecureSocket trackedTransferSocket = neoTransferSocket;
            executor.submit(() -> awaitTransformers(
                    TransportProtocol.TCP,
                    first,
                    second,
                    remoteAddress,
                    trackedLocalSocket,
                    trackedTransferSocket
            ));
        } catch (Exception e) {
            IOException exception;
            if (localServerSocket == null) {
                runCallback(onConnectLocalFailure);
                exception = toIOException("连接本地服务失败：" + runtimeCfg.getLocalDomainName() + ":" + runtimeCfg.getLocalPort(), e);
            } else if (neoTransferSocket == null) {
                runCallback(onConnectNeoFailure);
                exception = toIOException("连接 NeoProxyServer 传输端口失败："
                        + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort(), e);
            } else {
                exception = toIOException("创建 TCP 隧道失败。", e);
            }
            emitError(exception.getMessage(), exception);
            InternetOperator.close(localServerSocket, neoTransferSocket);
            unregisterConnection(localServerSocket, neoTransferSocket);
        }
    }

    private void createNewUDPConnection(String socketId, String remoteAddress) {
        SecureSocket neoTransferSocket = null;
        DatagramSocket datagramSocket = null;
        try {
            debug("Creating UDP tunnel. socketId=" + socketId + ", remoteAddress=" + remoteAddress);
            neoTransferSocket = openTransferSocket();
            datagramSocket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0));
            registerConnection(neoTransferSocket);
            registerConnection(datagramSocket);
            neoTransferSocket.sendStr("UDP" + ";" + socketId);

            emitConnectionEvent(onConnect, TransportProtocol.UDP, remoteAddress);

            UDPTransformer localToNeoTask = new UDPTransformer(
                    datagramSocket,
                    neoTransferSocket,
                    runtimeCfg.getLocalDomainName(),
                    runtimeCfg.getLocalPort(),
                    isDebugEnabled(),
                    this::emitDebug
            );
            UDPTransformer neoToLocalTask = new UDPTransformer(
                    neoTransferSocket,
                    datagramSocket,
                    runtimeCfg.getLocalDomainName(),
                    runtimeCfg.getLocalPort(),
                    isDebugEnabled(),
                    this::emitDebug
            );
            ExecutorService executor = requireWorkerExecutor();
            Future<?> first = executor.submit(localToNeoTask);
            Future<?> second = executor.submit(neoToLocalTask);
            SecureSocket trackedTransferSocket = neoTransferSocket;
            DatagramSocket trackedDatagramSocket = datagramSocket;
            executor.submit(() -> awaitUdpTransformers(
                    first,
                    second,
                    remoteAddress,
                    trackedTransferSocket,
                    trackedDatagramSocket
            ));
        } catch (Exception e) {
            IOException exception;
            if (neoTransferSocket == null) {
                runCallback(onConnectNeoFailure);
                exception = toIOException("连接 NeoProxyServer 传输端口失败："
                        + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort(), e);
            } else {
                exception = toIOException("创建 UDP 隧道失败。", e);
            }
            emitError(exception.getMessage(), exception);
            InternetOperator.close(datagramSocket, neoTransferSocket);
            unregisterConnection(datagramSocket, neoTransferSocket);
        }
    }

    private Socket openLocalSocket() throws IOException {
        if (proxyOperator.hasProxy(ProxyOperator.TO_LOCAL)) {
            debug("Connecting to local service through per-instance proxy. target="
                    + runtimeCfg.getLocalDomainName() + ":" + runtimeCfg.getLocalPort()
                    + ", timeoutMs=" + DEFAULT_CONNECT_TIMEOUT_MILLIS);
            return proxyOperator.getHandledSocket(
                    ProxyOperator.TO_LOCAL,
                    runtimeCfg.getLocalPort(),
                    DEFAULT_CONNECT_TIMEOUT_MILLIS
            );
        }
        return connectToLocalRobustly(runtimeCfg.getLocalDomainName(), runtimeCfg.getLocalPort());
    }

    private Socket connectToLocalRobustly(String host, int port) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        IOException lastException = null;
        for (InetAddress address : addresses) {
            Socket socket = new Socket();
            registerConnection(socket);
            try {
                debug("Trying local resolved address. address=" + address.getHostAddress()
                        + ", port=" + port + ", timeoutMs=" + DEFAULT_CONNECT_TIMEOUT_MILLIS);
                socket.connect(new InetSocketAddress(address, port), DEFAULT_CONNECT_TIMEOUT_MILLIS);
                return socket;
            } catch (IOException e) {
                lastException = e;
                debug("Local resolved address failed. address=" + address.getHostAddress()
                        + ", port=" + port + ", error=" + e.getMessage());
                InternetOperator.close(socket);
                unregisterConnection(socket);
            }
        }
        throw lastException != null ? lastException : new IOException("解析本地域名失败：" + host);
    }

    private SecureSocket openTransferSocket() throws IOException {
        int timeoutMillis = runtimeConnectToNpsTimeoutMillis;
        if (proxyOperator.hasProxy(ProxyOperator.TO_NEO)) {
            debug("Connecting to NeoProxyServer transfer port through per-instance proxy. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort()
                    + ", timeoutMs=" + timeoutMillis);
            return proxyOperator.getHandledSecureSocket(
                    ProxyOperator.TO_NEO,
                    runtimeCfg.getHostConnectPort(),
                    timeoutMillis
            );
        }

        Socket socket = new Socket();
        registerConnection(socket);
        try {
            debug("Connecting to NeoProxyServer transfer port directly. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort()
                    + ", timeoutMs=" + timeoutMillis);
            socket.connect(
                    new InetSocketAddress(runtimeCfg.getRemoteDomainName(), runtimeCfg.getHostConnectPort()),
                    timeoutMillis
            );
            unregisterConnection(socket);
            return new SecureSocket(socket);
        } catch (IOException e) {
            InternetOperator.close(socket);
            unregisterConnection(socket);
            throw e;
        }
    }

    private void awaitTransformers(
            TransportProtocol protocol,
            Future<?> first,
            Future<?> second,
            String remoteAddress,
            Closeable... trackedConnections
    ) {
        try {
            first.get();
        } catch (Exception e) {
            debug(e);
        }
        try {
            second.get();
        } catch (Exception e) {
            debug(e);
        } finally {
            InternetOperator.close(trackedConnections);
            unregisterConnection(trackedConnections);
            emitConnectionEvent(onDisconnect, protocol, remoteAddress);
        }
    }

    private void awaitUdpTransformers(
            Future<?> first,
            Future<?> second,
            String remoteAddress,
            Closeable... trackedConnections
    ) {
        try {
            waitForFirstCompletion(first, second);
        } finally {
            InternetOperator.close(trackedConnections);
            unregisterConnection(trackedConnections);
        }

        try {
            first.get();
        } catch (Exception e) {
            debug(e);
        }
        try {
            second.get();
        } catch (Exception e) {
            debug(e);
        } finally {
            emitConnectionEvent(onDisconnect, TransportProtocol.UDP, remoteAddress);
        }
    }

    private void waitForFirstCompletion(Future<?> first, Future<?> second) {
        while (true) {
            if (first.isDone() || second.isDone()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void submitWorker(Runnable task) {
        ExecutorService executor = workerExecutor;
        if (executor == null || executor.isShutdown() || !running.get()) {
            debug("Worker task ignored because executor is not available.");
            return;
        }

        try {
            executor.submit(task);
        } catch (RuntimeException e) {
            debug(e);
        }
    }

    private ExecutorService requireWorkerExecutor() throws IOException {
        ExecutorService executor = workerExecutor;
        if (executor == null || executor.isShutdown()) {
            throw new IOException("NeoLinkAPI 隧道已经停止，无法创建新的转发连接。");
        }
        return executor;
    }

    @Override
    public void close() {
        if (state != NeoLinkState.STOPPED) {
            transitionTo(NeoLinkState.STOPPING);
        }
        synchronized (lifecycleLock) {
            debug("close() requested.");
            closeRequested.set(true);
            running.set(false);
            CheckAliveThread activeCheckAliveThread = checkAliveThread;
            if (activeCheckAliveThread != null) {
                activeCheckAliveThread.stopThread();
            }
            closeActiveConnection();
            shutdownWorkerExecutor();
            pendingProtocolSwitch = null;
            pendingProtocolSwitchRollback = null;
            completeStartup(new IOException("NeoLinkAPI 启动已取消。"));
        }
    }

    private void awaitStartup()
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        try {
            startupFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 NeoLinkAPI 启动时线程被中断。", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof UnsupportedVersionException unsupportedVersionException) {
                throw unsupportedVersionException;
            }
            if (cause instanceof NoSuchKeyException noSuchKeyException) {
                throw noSuchKeyException;
            }
            if (cause instanceof NoMoreNetworkFlowException noMoreNetworkFlowException) {
                throw noMoreNetworkFlowException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("NeoLinkAPI 启动失败。", cause);
        }
    }

    private void awaitRuntime(Future<?> activeSupervisorFuture)
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        if (activeSupervisorFuture == null) {
            return;
        }

        try {
            activeSupervisorFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 NeoLinkAPI 运行结束时线程被中断。", e);
        } catch (CancellationException e) {
            if (!closeRequested.get()) {
                throw new IOException("NeoLinkAPI 运行任务被取消。", e);
            }
        } catch (ExecutionException e) {
            throw unwrapLifecycleFailure("NeoLinkAPI 运行失败。", e.getCause());
        }
    }

    private IOException unwrapLifecycleFailureAsIOException(String fallbackMessage, Throwable cause) {
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IOException(fallbackMessage, cause);
    }

    private <T extends Exception> T castLifecycleFailure(Throwable cause, Class<T> type) {
        return type.isInstance(cause) ? type.cast(cause) : null;
    }

    private IOException unwrapLifecycleFailure(String fallbackMessage, Throwable cause)
            throws UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        UnsupportedVersionException unsupportedVersionException =
                castLifecycleFailure(cause, UnsupportedVersionException.class);
        if (unsupportedVersionException != null) {
            throw unsupportedVersionException;
        }
        NoSuchKeyException noSuchKeyException = castLifecycleFailure(cause, NoSuchKeyException.class);
        if (noSuchKeyException != null) {
            throw noSuchKeyException;
        }
        NoMoreNetworkFlowException noMoreNetworkFlowException =
                castLifecycleFailure(cause, NoMoreNetworkFlowException.class);
        if (noMoreNetworkFlowException != null) {
            throw noMoreNetworkFlowException;
        }
        return unwrapLifecycleFailureAsIOException(fallbackMessage, cause);
    }

    private void completeStartup(Throwable failure) {
        completeStartup(lifecycleGeneration.get(), failure);
    }

    private void completeStartup(long generation, Throwable failure) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        CompletableFuture<Void> future = startupFuture;
        if (future == null || future.isDone()) {
            return;
        }
        if (failure == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(failure);
        }
    }

    private boolean isStartupComplete() {
        CompletableFuture<Void> future = startupFuture;
        return future != null && future.isDone() && !future.isCompletedExceptionally();
    }

    private void cleanupLifecycle(long generation) {
        boolean notifyStopped;
        synchronized (lifecycleLock) {
            if (!isCurrentGeneration(generation)) {
                debug("Ignoring cleanup from a stale lifecycle generation. generation=" + generation);
                return;
            }

            CheckAliveThread activeCheckAliveThread = checkAliveThread;
            if (activeCheckAliveThread != null) {
                activeCheckAliveThread.stopThread();
            }
            closeActiveConnection();
            running.set(false);
            shutdownWorkerExecutor();
            pendingProtocolSwitch = null;
            pendingProtocolSwitchRollback = null;
            resetTunAddrState();
            notifyStopped = moveStateTo(NeoLinkState.STOPPED);
        }
        if (notifyStopped) {
            emitStateChanged(NeoLinkState.STOPPED);
        }
    }

    private boolean isCurrentGeneration(long generation) {
        return lifecycleGeneration.get() == generation;
    }

    private void closeActiveConnection() {
        debug("Closing active control and transfer connections.");
        InternetOperator.close(connectingSocket, hookSocket);
        closeAllTrackedConnections();
        connectingSocket = null;
        hookSocket = null;
    }

    private void registerConnection(Closeable closeable) {
        if (closeable != null) {
            activeConnections.add(closeable);
        }
    }

    private void unregisterConnection(Closeable... closeables) {
        for (Closeable closeable : closeables) {
            if (closeable != null) {
                activeConnections.remove(closeable);
            }
        }
    }

    private void closeAllTrackedConnections() {
        for (Closeable closeable : activeConnections.toArray(Closeable[]::new)) {
            InternetOperator.close(closeable);
            unregisterConnection(closeable);
        }
    }

    private void shutdownWorkerExecutor() {
        ExecutorService activeExecutor = workerExecutor;
        if (activeExecutor != null) {
            activeExecutor.shutdownNow();
        }
        workerExecutor = null;
    }

    private void emitConnectionEvent(ConnectionEventHandler handler, TransportProtocol protocol, String remoteAddress) {
        NeoLinkCfg activeCfg = runtimeCfg;
        InetSocketAddress target = InetSocketAddress.createUnresolved(
                activeCfg.getLocalDomainName(),
                activeCfg.getLocalPort()
        );
        try {
            handler.accept(protocol, parseRemoteAddress(remoteAddress), target);
        } catch (RuntimeException e) {
            debug(e);
        }
    }

    private void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            debug(e);
        }
    }

    private void emitError(String message, Throwable cause) {
        try {
            onError.accept(message, cause);
        } catch (RuntimeException e) {
            debug(e);
        }
        debug("ERROR: " + message);
        if (cause instanceof Exception exception) {
            debug(exception);
        }
    }

    private void emitServerMessage(String message) {
        try {
            onServerMessage.accept(message);
        } catch (RuntimeException e) {
            debug(e);
        }
    }

    private void captureTunAddrIfPresent(String message) {
        String parsedTunAddr = parseTunAddrMessage(message);
        if (parsedTunAddr == null) {
            return;
        }
        tunAddr = parsedTunAddr;
        tunAddrFuture.complete(parsedTunAddr);
        debug("Tunnel address received from NeoProxyServer. value=" + parsedTunAddr);
    }

    private void resetTunAddrState() {
        CompletableFuture<String> previousFuture = tunAddrFuture;
        tunAddr = null;
        tunAddrFuture = new CompletableFuture<>();
        if (previousFuture != null && !previousFuture.isDone()) {
            previousFuture.cancel(false);
        }
    }

    private void captureTerminalServerMessage(String message) {
        if (classifyRuntimeTerminalFailure(message) != null) {
            lastServerTerminalMessage = message;
        }
    }

    private boolean isDispatchEnabled(TransportProtocol protocol) {
        settlePendingProtocolSwitchIfExpired();
        NeoLinkCfg activeCfg = runtimeCfg;
        boolean configuredEnabled = protocol == TransportProtocol.TCP
                ? activeCfg.isTCPEnabled()
                : activeCfg.isUDPEnabled();
        ProtocolSwitch activeSwitch = pendingProtocolSwitch;
        if (activeSwitch == null) {
            return configuredEnabled;
        }
        return activeSwitch.accepts(protocol);
    }

    private void settlePendingProtocolSwitchIfExpired() {
        ProtocolSwitch activeSwitch = pendingProtocolSwitch;
        if (activeSwitch == null || !activeSwitch.isExpired(System.currentTimeMillis())) {
            return;
        }
        pendingProtocolSwitch = null;
        pendingProtocolSwitchRollback = null;
        debug("Runtime protocol-switch grace window elapsed. committedFlags="
                + activeSwitch.requested().asProtocolFlags());
    }

    private boolean shouldRequestUnsupportedVersionUpdate(String serverResponse) {
        try {
            return Boolean.TRUE.equals(unsupportedVersionDecision.apply(serverResponse));
        } catch (RuntimeException e) {
            debug(e);
            return false;
        }
    }

    private void transitionTo(NeoLinkState newState) {
        if (moveStateTo(newState)) {
            emitStateChanged(newState);
        }
    }

    private boolean moveStateTo(NeoLinkState newState) {
        Objects.requireNonNull(newState, "newState");
        if (state == newState) {
            return false;
        }
        state = newState;
        return true;
    }

    private void emitStateChanged(NeoLinkState newState) {
        try {
            onStateChanged.accept(newState);
        } catch (RuntimeException e) {
            debug(e);
        }
    }

    private NeoLinkCfg requireRuntimeCfg() throws IOException {
        NeoLinkCfg activeCfg = runtimeCfg;
        if (activeCfg == null) {
            throw new IOException("NeoLinkAPI runtime configuration is not available.");
        }
        return activeCfg;
    }

    private SecureSocket requireActiveHookSocket() throws IOException {
        SecureSocket activeHookSocket = hookSocket;
        if (!running.get() || activeHookSocket == null || activeHookSocket.isClosed()) {
            throw new IOException("NeoLinkAPI control channel is not active.");
        }
        return activeHookSocket;
    }

    private void rollbackRuntimeProtocolSwitchIfRejected(String message) {
        if (!isNoMorePortResponse(message)) {
            return;
        }
        ProtocolFlags rollbackFlags = pendingProtocolSwitchRollback;
        NeoLinkCfg activeCfg = runtimeCfg;
        if (rollbackFlags == null || activeCfg == null) {
            return;
        }
        activeCfg.setTCPEnabled(rollbackFlags.tcpEnabled());
        activeCfg.setUDPEnabled(rollbackFlags.udpEnabled());
        pendingProtocolSwitchRollback = null;
        pendingProtocolSwitch = null;
        debug("Runtime protocol-switch rejected by NeoProxyServer. Rolled local flags back to "
                + rollbackFlags.asProtocolFlags());
    }

    private boolean isDebugEnabled() {
        NeoLinkCfg activeCfg = runtimeCfg;
        return Debugger.isEnabled() || (activeCfg != null ? activeCfg.isDebugMsg() : cfg.isDebugMsg());
    }

    private void debug(String message) {
        if (!isDebugEnabled()) {
            return;
        }
        emitDebug(message, null);
    }

    private void debug(Exception e) {
        if (!isDebugEnabled() || e == null) {
            return;
        }
        emitDebug(null, e);
    }

    private void emitDebug(String message, Throwable cause) {
        try {
            debugSink.accept(message, cause);
        } catch (RuntimeException ignored) {
            // Debug 回调只用于观测，不应影响隧道状态。
        }
    }

    private String describeRuntimeConfig() {
        NeoLinkCfg activeCfg = runtimeCfg;
        return "remote=" + activeCfg.getRemoteDomainName()
                + ", hookPort=" + activeCfg.getHookPort()
                + ", connectPort=" + activeCfg.getHostConnectPort()
                + ", local=" + activeCfg.getLocalDomainName() + ":" + activeCfg.getLocalPort()
                + ", language=" + activeCfg.getLanguage()
                + ", tcpEnabled=" + activeCfg.isTCPEnabled()
                + ", udpEnabled=" + activeCfg.isUDPEnabled()
                + ", ppv2Enabled=" + activeCfg.isPPV2Enabled()
                + ", heartbeatDelayMs=" + activeCfg.getHeartBeatPacketDelay()
                + ", proxyToNeo=" + (activeCfg.getProxyIPToNeoServer().isBlank() ? "direct" : "configured")
                + ", proxyToLocal=" + (activeCfg.getProxyIPToLocalServer().isBlank() ? "direct" : "configured")
                + ", connectToNpsTimeoutMs=" + runtimeConnectToNpsTimeoutMillis
                + ", connectToLocalTimeoutMs=" + DEFAULT_CONNECT_TIMEOUT_MILLIS;
    }

    /**
     * 转发连接使用的传输协议类型。
     */
    public enum TransportProtocol {
        TCP,
        UDP
    }

    /**
     * 接收转发连接事件，协议类型由 NeoLinkAPI 解析后传入。
     */
    @FunctionalInterface
    public interface ConnectionEventHandler {
        void accept(TransportProtocol protocol, InetSocketAddress source, InetSocketAddress target);
    }

    private record ProtocolFlags(boolean tcpEnabled, boolean udpEnabled) {
        static ProtocolFlags from(NeoLinkCfg cfg) {
            return new ProtocolFlags(cfg.isTCPEnabled(), cfg.isUDPEnabled());
        }

        boolean enabled(TransportProtocol protocol) {
            return protocol == TransportProtocol.TCP ? tcpEnabled : udpEnabled;
        }

        String asProtocolFlags() {
            StringBuilder flags = new StringBuilder(2);
            if (tcpEnabled) {
                flags.append('T');
            }
            if (udpEnabled) {
                flags.append('U');
            }
            return flags.toString();
        }
    }

    private record ProtocolSwitch(
            ProtocolFlags previous,
            ProtocolFlags requested,
            long expiresAtMillis
    ) {
        boolean accepts(TransportProtocol protocol) {
            return previous.enabled(protocol) || requested.enabled(protocol);
        }

        boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

}
