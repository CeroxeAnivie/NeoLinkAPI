package top.ceroxe.api.neolink;

import top.ceroxe.api.OshiUtils;
import top.ceroxe.api.net.SecureSocket;
import top.ceroxe.api.neolink.exception.NoMoreNetworkFlowException;
import top.ceroxe.api.neolink.exception.NoSuchKeyException;
import top.ceroxe.api.neolink.exception.UnsupportedVersionException;
import top.ceroxe.api.neolink.network.InternetOperator;
import top.ceroxe.api.neolink.network.ProxyOperator;
import top.ceroxe.api.neolink.network.threads.CheckAliveThread;
import top.ceroxe.api.neolink.network.threads.TCPTransformer;
import top.ceroxe.api.neolink.network.threads.UDPTransformer;
import top.ceroxe.api.neolink.util.Debugger;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

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
    private static final String NO_UPDATE_URL_RESPONSE = "false";
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int UPDATE_URL_TIMEOUT_MILLIS = 15_000;
    private static final Runnable NOOP = () -> {
    };
    private static final Consumer<NeoLinkState> NOOP_STATE_HANDLER = state -> {
    };
    private static final Consumer<String> NOOP_MESSAGE_HANDLER = message -> {
    };
    private static final IntConsumer NOOP_PORT_HANDLER = port -> {
    };
    private static final ConnectionEventHandler NOOP_CONNECTION_EVENT_HANDLER = (protocol, source, target) -> {
    };
    private static final BiConsumer<String, Throwable> NOOP_ERROR_HANDLER = (message, cause) -> {
    };
    private static final Function<String, Boolean> REQUEST_UNSUPPORTED_VERSION_UPDATE = response -> true;

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
    private volatile long lastReceivedTime = System.currentTimeMillis();
    private volatile int remotePort;
    private volatile NeoLinkState state = NeoLinkState.STOPPED;

    private volatile ConnectionEventHandler onConnect = NOOP_CONNECTION_EVENT_HANDLER;
    private volatile ConnectionEventHandler onDisconnect = NOOP_CONNECTION_EVENT_HANDLER;
    private volatile Runnable onConnectNeoFailure = NOOP;
    private volatile Runnable onConnectLocalFailure = NOOP;
    private volatile Consumer<NeoLinkState> onStateChanged = NOOP_STATE_HANDLER;
    private volatile BiConsumer<String, Throwable> onError = NOOP_ERROR_HANDLER;
    private volatile Consumer<String> onServerMessage = NOOP_MESSAGE_HANDLER;
    private volatile IntConsumer onRemotePortChanged = NOOP_PORT_HANDLER;
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
     * 建立控制连接、完成握手并启动服务端指令监听。
     *
     * <p>该方法会阻塞到握手成功或失败。TCP/UDP、PPv2、语言和 debug 等协议相关
     * 配置必须在调用本方法之前写入 {@link NeoLinkCfg}，因为它们会参与握手或转发
     * 线程初始化。</p>
     *
     * @throws IOException 控制连接、传输连接或本地下游连接发生 I/O 错误
     * @throws UnsupportedVersionException 服务端拒绝当前 API 版本
     * @throws NoSuchKeyException 服务端拒绝访问密钥
     * @throws NoMoreNetworkFlowException 服务端通知剩余流量耗尽
     */
    public void start()
            throws IOException, UnsupportedVersionException, NoSuchKeyException, NoMoreNetworkFlowException {
        boolean notifyStarting;
        synchronized (lifecycleLock) {
            if (isActive()) {
                debug("start() ignored because this NeoLinkAPI instance is already active.");
                return;
            }

            long generation = lifecycleGeneration.incrementAndGet();
            runtimeCfg = cfg.copy();
            updateUrl = null;
            remotePort = 0;
            notifyStarting = moveStateTo(NeoLinkState.STARTING);
            debug("Starting NeoLinkAPI tunnel. " + describeRuntimeConfig());
            proxyOperator = new ProxyOperator(
                    runtimeCfg.getRemoteDomainName(),
                    runtimeCfg.getLocalDomainName(),
                    runtimeCfg.getProxyIPToNeoServer(),
                    runtimeCfg.getProxyIPToLocalServer()
            );
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
            supervisorFuture = workerExecutor.submit(() -> runCore(generation));
        }
        if (notifyStarting) {
            emitStateChanged(NeoLinkState.STARTING);
        }

        try {
            awaitStartup();
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
     * 返回服务端下发的公网端口。
     *
     * @return 尚未收到端口时返回 {@code 0}
     */
    public int getRemotePort() {
        return remotePort;
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
     * 设置公网端口更新回调。
     *
     * @param onRemotePortChanged 服务端下发公网端口时触发
     * @return 当前隧道对象，便于链式调用
     */
    public NeoLinkAPI setOnRemotePortChanged(IntConsumer onRemotePortChanged) {
        this.onRemotePortChanged = Objects.requireNonNull(onRemotePortChanged, "onRemotePortChanged");
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
     * Sets callback executed when a forwarding connection is established.
     *
     * @param onConnect callback receiving protocol, remote visitor address and local downstream address
     * @return current tunnel instance for chaining
     */
    public NeoLinkAPI setOnConnect(ConnectionEventHandler onConnect) {
        this.onConnect = Objects.requireNonNull(onConnect, "onConnect");
        return this;
    }

    /**
     * Sets callback executed when a forwarding connection is closed.
     *
     * @param onDisconnect callback receiving protocol, remote visitor address and local downstream address
     * @return current tunnel instance for chaining
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
     * 返回当前 API 包版本。
     *
     * @return 从 {@code api.properties} 解析出的版本号
     */
    public static String version() {
        return VersionInfo.VERSION;
    }

    /**
     * Transport type used by a forwarding connection.
     */
    public enum TransportProtocol {
        TCP,
        UDP
    }

    /**
     * Receives forwarding connection events with the protocol resolved by NeoLinkAPI.
     */
    @FunctionalInterface
    public interface ConnectionEventHandler {
        void accept(TransportProtocol protocol, InetSocketAddress source, InetSocketAddress target);
    }

    private void runCore(long generation) {
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
            completeStartup(generation, e);
            closeRequested.set(true);
            transitionTo(NeoLinkState.FAILED);
            emitError(e.getMessage(), e);
        } catch (IOException e) {
            completeStartup(generation, e);
            if (running.get() && !closeRequested.get()) {
                transitionTo(NeoLinkState.FAILED);
                emitError("NeoLinkAPI 隧道异常停止。", e);
                debug(e);
            }
        } catch (Exception e) {
            IOException exception = toIOException("NeoLinkAPI 隧道异常停止。", e);
            completeStartup(generation, exception);
            if (running.get() && !closeRequested.get()) {
                transitionTo(NeoLinkState.FAILED);
                emitError(exception.getMessage(), exception);
                debug(exception);
            }
        } finally {
            cleanupLifecycle(generation);
        }
    }

    private void connectToNeoServer() throws IOException {
        if (proxyOperator.hasProxy(ProxyOperator.TO_NEO)) {
            debug("Connecting to NeoProxyServer hook through per-instance proxy. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHookPort());
            hookSocket = proxyOperator.getHandledSecureSocket(
                    ProxyOperator.TO_NEO,
                    runtimeCfg.getHookPort(),
                    CONNECT_TIMEOUT_MILLIS
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
                    + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
            socket.connect(
                    new InetSocketAddress(runtimeCfg.getRemoteDomainName(), runtimeCfg.getHookPort()),
                    CONNECT_TIMEOUT_MILLIS
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

        if (isUnsupportedVersionResponse(serverResponse)) {
            handleUnsupportedVersionResponse(serverResponse);
            throw new UnsupportedVersionException(serverResponse);
        }

        if (isNoMoreNetworkFlowResponse(serverResponse)) {
            throw new NoMoreNetworkFlowException(serverResponse);
        }

        if (isNoSuchKeyResponse(serverResponse)) {
            throw new NoSuchKeyException(serverResponse);
        }

        if (isTerminalServerResponse(serverResponse)) {
            throw new IOException("NeoProxyServer 拒绝建立隧道：" + serverResponse);
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
                .append(';');
        if (runtimeCfg.isTCPEnabled()) {
            info.append('T');
        }
        if (runtimeCfg.isUDPEnabled()) {
            info.append('U');
        }
        return info.toString();
    }

    private void listenForServerCommands() throws IOException, NoMoreNetworkFlowException {
        String message;
        while (running.get() && (message = hookSocket.receiveStr()) != null) {
            lastReceivedTime = System.currentTimeMillis();
            debug("Received server message. value=" + message);
            if (message.startsWith(":>")) {
                handleServerCommand(message.substring(2));
            } else {
                emitServerMessage(message);
                debug("Ignored non-command server message.");
            }
        }
        throw new IOException("NeoProxyServer 连接已关闭。");
    }

    private void handleServerCommand(String command) throws NoMoreNetworkFlowException {
        String[] parts = command.split(";");
        switch (parts[0]) {
            case "sendSocketTCP" -> {
                if (runtimeCfg.isTCPEnabled() && parts.length >= 3) {
                    debug("Scheduling TCP tunnel. socketId=" + parts[1] + ", remoteAddress=" + parts[2]);
                    submitWorker(() -> createNewTCPConnection(parts[1], parts[2]));
                } else {
                    debug("Ignored TCP tunnel command because TCP is disabled or command is malformed.");
                }
            }
            case "sendSocketUDP" -> {
                if (runtimeCfg.isUDPEnabled() && parts.length >= 3) {
                    debug("Scheduling UDP tunnel. socketId=" + parts[1] + ", remoteAddress=" + parts[2]);
                    submitWorker(() -> createNewUDPConnection(parts[1], parts[2]));
                } else {
                    debug("Ignored UDP tunnel command because UDP is disabled or command is malformed.");
                }
            }
            case "exitNoFlow" -> {
                closeRequested.set(true);
                running.set(false);
                NoMoreNetworkFlowException exception = new NoMoreNetworkFlowException();
                emitError(exception.getMessage(), exception);
                closeActiveConnection();
                throw exception;
            }
            default -> updateRemotePort(parts[0]);
        }
    }

    private void updateRemotePort(String value) {
        try {
            setRemotePort(Integer.parseInt(value));
            debug("Remote public port updated. remotePort=" + remotePort);
        } catch (NumberFormatException e) {
            debug("Ignored unknown server command. value=" + value);
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
            executor.submit(() -> awaitTransformers(
                    TransportProtocol.UDP,
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
                    + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
            return proxyOperator.getHandledSocket(
                    ProxyOperator.TO_LOCAL,
                    runtimeCfg.getLocalPort(),
                    CONNECT_TIMEOUT_MILLIS
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
                        + ", port=" + port + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
                socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
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
        if (proxyOperator.hasProxy(ProxyOperator.TO_NEO)) {
            debug("Connecting to NeoProxyServer transfer port through per-instance proxy. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort()
                    + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
            return proxyOperator.getHandledSecureSocket(
                    ProxyOperator.TO_NEO,
                    runtimeCfg.getHostConnectPort(),
                    CONNECT_TIMEOUT_MILLIS
            );
        }

        Socket socket = new Socket();
        registerConnection(socket);
        try {
            debug("Connecting to NeoProxyServer transfer port directly. target="
                    + runtimeCfg.getRemoteDomainName() + ":" + runtimeCfg.getHostConnectPort()
                    + ", timeoutMs=" + CONNECT_TIMEOUT_MILLIS);
            socket.connect(
                    new InetSocketAddress(runtimeCfg.getRemoteDomainName(), runtimeCfg.getHostConnectPort()),
                    CONNECT_TIMEOUT_MILLIS
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
        remotePort = 0;
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

    private void setRemotePort(int remotePort) {
        if (this.remotePort == remotePort) {
            return;
        }
        this.remotePort = remotePort;
        try {
            onRemotePortChanged.accept(remotePort);
        } catch (RuntimeException e) {
            debug(e);
        }
    }

    private boolean shouldRequestUnsupportedVersionUpdate(String serverResponse) {
        try {
            return Boolean.TRUE.equals(unsupportedVersionDecision.apply(serverResponse));
        } catch (RuntimeException e) {
            debug(e);
            return false;
        }
    }

    private static String normalizeUpdateURL(String response) {
        if (response == null) {
            return null;
        }

        String normalized = response.trim();
        if (normalized.isEmpty() || NO_UPDATE_URL_RESPONSE.equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private static String updateClientType() {
        return OshiUtils.isWindows() ? WINDOWS_UPDATE_CLIENT_TYPE : DEFAULT_UPDATE_CLIENT_TYPE;
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

    private static boolean isUnsupportedVersionResponse(String response) {
        return response.contains("nsupported")
                || response.contains("不")
                || response.contains("旧");
    }

    private static boolean isNoMoreNetworkFlowResponse(String response) {
        return response.contains("exitNoFlow")
                || response.contains("No extra network traffic left")
                || response.contains("没有多余的流量")
                || response.contains("流量耗尽");
    }

    private static boolean isNoSuchKeyResponse(String response) {
        return isTerminalServerResponse(response);
    }

    private static boolean isTerminalServerResponse(String response) {
        return response.contains("exit")
                || response.contains("退")
                || response.contains("错误")
                || response.contains("denied")
                || response.contains("already")
                || response.contains("过期")
                || response.contains("占");
    }

    private static IOException toIOException(String message, Exception cause) {
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(message, cause);
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
            // Debug callbacks are observational and must not affect tunnel state.
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
                + ", connectTimeoutMs=" + CONNECT_TIMEOUT_MILLIS;
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
}
