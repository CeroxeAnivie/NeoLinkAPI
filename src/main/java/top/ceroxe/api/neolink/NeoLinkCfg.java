package top.ceroxe.api.neolink;

/**
 * NeoLinkAPI 对外暴露的可变配置对象。
 *
 * <p>构造函数中的五个参数没有安全的 API 默认值，因此强制调用方显式传入：
 * {@code REMOTE_DOMAIN_NAME}、{@code HOST_HOOK_PORT}、
 * {@code HOST_CONNECT_PORT}、访问密钥 {@code key} 和本地服务端口
 * {@code localPort}。其余配置继续沿用原运行时默认值，避免 API 使用方为了
 * “空代理”和“标准心跳间隔”重复写样板代码。</p>
 */
public final class NeoLinkCfg {
    /**
     * 英文协议语言标识。该值会写入客户端握手包的第一段。
     */
    public static final String EN_US = "en";

    /**
     * 中文协议语言标识。保留 {@code ZH_CH} 名称是为了匹配现有 NeoLinkAPI 生态中的口径。
     */
    public static final String ZH_CH = "zh";

    public static final String DEFAULT_PROXY_IP = "";
    public static final String DEFAULT_LOCAL_DOMAIN_NAME = "localhost";
    public static final int DEFAULT_HEARTBEAT_PACKET_DELAY = 1000;

    private String remoteDomainName;
    private int hookPort;
    private int hostConnectPort;
    private String localDomainName = DEFAULT_LOCAL_DOMAIN_NAME;
    private int localPort;
    private String key;
    private String proxyIPToLocalServer = DEFAULT_PROXY_IP;
    private String proxyIPToNeoServer = DEFAULT_PROXY_IP;
    private int heartBeatPacketDelay = DEFAULT_HEARTBEAT_PACKET_DELAY;
    private boolean tcpEnabled = true;
    private boolean udpEnabled = true;
    private boolean ppv2Enabled = false;
    private boolean debugMsg = false;
    private String language = ZH_CH;
    private String clientVersion = VersionInfo.VERSION;

    /**
     * 创建一个最小可用的 NeoLinkAPI 隧道配置。
     *
     * <p>这些参数没有可靠的通用默认值，因此强制调用方显式传入。所有端口都会按
     * TCP/UDP 合法端口范围校验，避免错误配置延迟到网络调用阶段才暴露。</p>
     *
     * @param remoteDomainName NeoProxyServer 的域名或 IP
     * @param hookPort 控制连接端口
     * @param hostConnectPort TCP/UDP 数据传输连接端口
     * @param key 访问密钥
     * @param localPort 本地下游服务端口
     * @throws IllegalArgumentException 任一文本参数为空白，或端口不在 1 到 65535 时抛出
     */
    public NeoLinkCfg(String remoteDomainName, int hookPort, int hostConnectPort, String key, int localPort) {
        this.remoteDomainName = requireText(remoteDomainName, "remoteDomainName");
        this.hookPort = requirePort(hookPort, "hookPort");
        this.hostConnectPort = requirePort(hostConnectPort, "hostConnectPort");
        this.key = requireText(key, "key");
        this.localPort = requirePort(localPort, "localPort");
    }

    private NeoLinkCfg(NeoLinkCfg source) {
        synchronized (source) {
            this.remoteDomainName = source.remoteDomainName;
            this.hookPort = source.hookPort;
            this.hostConnectPort = source.hostConnectPort;
            this.localDomainName = source.localDomainName;
            this.localPort = source.localPort;
            this.key = source.key;
            this.proxyIPToLocalServer = source.proxyIPToLocalServer;
            this.proxyIPToNeoServer = source.proxyIPToNeoServer;
            this.heartBeatPacketDelay = source.heartBeatPacketDelay;
            this.tcpEnabled = source.tcpEnabled;
            this.udpEnabled = source.udpEnabled;
            this.ppv2Enabled = source.ppv2Enabled;
            this.debugMsg = source.debugMsg;
            this.language = source.language;
            this.clientVersion = source.clientVersion;
        }
    }

    NeoLinkCfg copy() {
        return new NeoLinkCfg(this);
    }

    /**
     * 返回 NeoProxyServer 的域名或 IP。
     *
     * @return 远端服务地址
     */
    public synchronized String getRemoteDomainName() {
        return remoteDomainName;
    }

    /**
     * 设置 NeoProxyServer 的域名或 IP。
     *
     * @param remoteDomainName 非空白远端地址
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setRemoteDomainName(String remoteDomainName) {
        this.remoteDomainName = requireText(remoteDomainName, "remoteDomainName");
        return this;
    }

    /**
     * 返回控制连接端口。
     *
     * @return 控制连接端口
     */
    public synchronized int getHookPort() {
        return hookPort;
    }

    /**
     * 设置控制连接端口。
     *
     * @param hookPort 1 到 65535 之间的端口
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setHookPort(int hookPort) {
        this.hookPort = requirePort(hookPort, "hookPort");
        return this;
    }

    /**
     * 返回数据传输连接端口。
     *
     * @return 数据传输端口
     */
    public synchronized int getHostConnectPort() {
        return hostConnectPort;
    }

    /**
     * 设置数据传输连接端口。
     *
     * @param hostConnectPort 1 到 65535 之间的端口
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setHostConnectPort(int hostConnectPort) {
        this.hostConnectPort = requirePort(hostConnectPort, "hostConnectPort");
        return this;
    }

    /**
     * 返回本地下游服务地址。
     *
     * @return 本地下游服务域名或 IP
     */
    public synchronized String getLocalDomainName() {
        return localDomainName;
    }

    /**
     * 设置本地下游服务的域名或 IP。
     *
     * <p>默认值为 {@code localhost}。当下游服务只监听 IPv4 或 IPv6 的其中一侧时，
     * 可以显式传入 {@code 127.0.0.1}、{@code ::1} 或业务域名。</p>
     *
     * @param localDomainName 非空白本地服务地址
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setLocalDomainName(String localDomainName) {
        this.localDomainName = requireText(localDomainName, "localDomainName");
        return this;
    }

    /**
     * 返回本地下游服务端口。
     *
     * @return 本地服务端口
     */
    public synchronized int getLocalPort() {
        return localPort;
    }

    /**
     * 设置本地下游服务端口。
     *
     * @param localPort 1 到 65535 之间的端口
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setLocalPort(int localPort) {
        this.localPort = requirePort(localPort, "localPort");
        return this;
    }

    /**
     * 返回访问密钥。
     *
     * @return 访问密钥原文
     */
    public synchronized String getKey() {
        return key;
    }

    /**
     * 设置访问密钥。
     *
     * @param key 非空白访问密钥
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setKey(String key) {
        this.key = requireText(key, "key");
        return this;
    }

    /**
     * 返回连接本地下游服务时使用的代理配置。
     *
     * @return 代理配置字符串，空字符串表示直连
     */
    public synchronized String getProxyIPToLocalServer() {
        return proxyIPToLocalServer;
    }

    /**
     * 清空连接本地下游服务时使用的代理。
     *
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setProxyIPToLocalServer() {
        return setProxyIPToLocalServer(DEFAULT_PROXY_IP);
    }

    /**
     * 设置连接本地下游服务时使用的代理。
     *
     * <p>格式为 {@code socks->host:port}、{@code http->host:port}，
     * 带认证时使用 {@code socks->host:port@user;password}。空值表示直连。</p>
     *
     * @param proxyIPToLocalServer 代理配置字符串，允许为 {@code null} 或空白
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setProxyIPToLocalServer(String proxyIPToLocalServer) {
        this.proxyIPToLocalServer = nullToEmpty(proxyIPToLocalServer);
        return this;
    }

    /**
     * 返回连接 NeoProxyServer 时使用的代理配置。
     *
     * @return 代理配置字符串，空字符串表示直连
     */
    public synchronized String getProxyIPToNeoServer() {
        return proxyIPToNeoServer;
    }

    /**
     * 清空连接 NeoProxyServer 时使用的代理。
     *
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setProxyIPToNeoServer() {
        return setProxyIPToNeoServer(DEFAULT_PROXY_IP);
    }

    /**
     * 设置连接 NeoProxyServer 控制端口和传输端口时使用的代理。
     *
     * <p>该配置只属于当前 {@link NeoLinkAPI} 实例，不会写入全局静态配置。</p>
     *
     * @param proxyIPToNeoServer 代理配置字符串，允许为 {@code null} 或空白
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setProxyIPToNeoServer(String proxyIPToNeoServer) {
        this.proxyIPToNeoServer = nullToEmpty(proxyIPToNeoServer);
        return this;
    }

    /**
     * 返回心跳检测间隔。
     *
     * @return 心跳检测间隔，单位毫秒
     */
    public synchronized int getHeartBeatPacketDelay() {
        return heartBeatPacketDelay;
    }

    /**
     * 设置心跳检测间隔。
     *
     * @param heartBeatPacketDelay 正整数，单位为毫秒
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setHeartBeatPacketDelay(int heartBeatPacketDelay) {
        this.heartBeatPacketDelay = requirePositive(heartBeatPacketDelay, "heartBeatPacketDelay");
        return this;
    }

    /**
     * 返回 TCP 是否会在握手阶段声明启用。
     *
     * @return {@code true} 表示服务端可以向该客户端派发 TCP 转发连接
     */
    public synchronized boolean isTCPEnabled() {
        return tcpEnabled;
    }

    /**
     * 设置 TCP 转发开关。
     *
     * <p>该值会进入启动握手包，因此必须在创建 {@link NeoLinkAPI} 或调用
     * {@link NeoLinkAPI#start()} 之前完成配置。</p>
     *
     * @param tcpEnabled 是否启用 TCP 转发
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setTCPEnabled(boolean tcpEnabled) {
        this.tcpEnabled = tcpEnabled;
        return this;
    }

    /**
     * 返回 UDP 是否会在握手阶段声明启用。
     *
     * @return {@code true} 表示服务端可以向该客户端派发 UDP 转发连接
     */
    public synchronized boolean isUDPEnabled() {
        return udpEnabled;
    }

    /**
     * 设置 UDP 转发开关。
     *
     * @param udpEnabled 是否启用 UDP 转发
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setUDPEnabled(boolean udpEnabled) {
        this.udpEnabled = udpEnabled;
        return this;
    }

    /**
     * 返回是否向本地下游透传 Proxy Protocol v2 头。
     *
     * @return {@code true} 表示本地下游必须能够理解 Proxy Protocol v2
     */
    public synchronized boolean isPPV2Enabled() {
        return ppv2Enabled;
    }

    /**
     * 启用 Proxy Protocol v2 透传。
     *
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setPPV2Enabled() {
        return setPPV2Enabled(true);
    }

    /**
     * 设置 Proxy Protocol v2 透传开关。
     *
     * <p>默认关闭是为了保护 SSH、RDP、Minecraft 等不理解 PPv2 的普通后端；
     * 只有 Nginx、HAProxy 等已配置 accept-proxy 的后端才应该开启。</p>
     *
     * @param ppv2Enabled 是否透传 PPv2 头
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setPPV2Enabled(boolean ppv2Enabled) {
        this.ppv2Enabled = ppv2Enabled;
        return this;
    }

    /**
     * 返回握手语言标识。
     *
     * @return {@link #ZH_CH} 或 {@link #EN_US}
     */
    public synchronized String getLanguage() {
        return language;
    }

    /**
     * 设置握手语言标识。
     *
     * <p>当前协议只需要传递 {@code zh} 或 {@code en}。这里同时接受
     * {@code zh-cn}、{@code zh_ch}、{@code en-us}、{@code en_us} 等常见别名，
     * 统一归一化为服务端协议字段。</p>
     *
     * @param language 语言标识
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setLanguage(String language) {
        this.language = normalizeLanguage(language);
        return this;
    }

    /**
     * 返回握手阶段上报给 NeoProxyServer 的客户端版本。
     *
     * @return 默认值为当前 NeoLinkAPI 包版本
     */
    public synchronized String getClientVersion() {
        return clientVersion;
    }

    /**
     * 设置握手阶段上报给 NeoProxyServer 的客户端版本。
     *
     * <p>普通调用方不需要修改该值。桌面客户端、测试工具或兼容性探针可以通过它
     * 精确控制握手版本，避免把“版本上报策略”硬编码在 API 内部。</p>
     *
     * @param clientVersion 非空白版本字符串
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setClientVersion(String clientVersion) {
        this.clientVersion = requireText(clientVersion, "clientVersion");
        return this;
    }

    /**
     * 返回是否输出详细英文调试日志。
     *
     * @return {@code true} 表示当前隧道实例会输出连接、握手、转发和关闭细节
     */
    public synchronized boolean isDebugMsg() {
        return debugMsg;
    }

    /**
     * 启用详细英文调试日志。
     *
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setDebugMsg() {
        return setDebugMsg(true);
    }

    /**
     * 设置详细英文调试日志开关。
     *
     * @param debugMsg 是否输出调试日志
     * @return 当前配置对象，便于链式调用
     */
    public synchronized NeoLinkCfg setDebugMsg(boolean debugMsg) {
        this.debugMsg = debugMsg;
        return this;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }
        return value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static int requirePort(int value, String fieldName) {
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 65535.");
        }
        return value;
    }

    private static int requirePositive(int value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0.");
        }
        return value;
    }

    private static String normalizeLanguage(String value) {
        String normalized = requireText(value, "language")
                .toLowerCase()
                .replace('-', '_');
        return switch (normalized) {
            case EN_US, "en_us", "english" -> EN_US;
            case ZH_CH, "zh_ch", "zh_cn", "chinese" -> ZH_CH;
            default -> throw new IllegalArgumentException("language must be either NeoLinkCfg.EN_US or NeoLinkCfg.ZH_CH.");
        };
    }
}
