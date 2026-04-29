package top.ceroxe.api.neolink;

/**
 * NeoLink 对外暴露的可变配置对象。
 *
 * <p>构造函数中的五个参数没有安全的 API 默认值，因此强制调用方显式传入：
 * {@code REMOTE_DOMAIN_NAME}、{@code HOST_HOOK_PORT}、
 * {@code HOST_CONNECT_PORT}、访问密钥 {@code key} 和本地服务端口
 * {@code localPort}。其余配置继续沿用原运行时默认值，避免 API 使用方为了
 * “空代理”和“标准心跳间隔”重复写样板代码。</p>
 */
public final class NeoLinkCfg {
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
        }
    }

    NeoLinkCfg copy() {
        return new NeoLinkCfg(this);
    }

    public synchronized String getRemoteDomainName() {
        return remoteDomainName;
    }

    public synchronized NeoLinkCfg setRemoteDomainName(String remoteDomainName) {
        this.remoteDomainName = requireText(remoteDomainName, "remoteDomainName");
        return this;
    }

    public synchronized int getHookPort() {
        return hookPort;
    }

    public synchronized NeoLinkCfg setHookPort(int hookPort) {
        this.hookPort = requirePort(hookPort, "hookPort");
        return this;
    }

    public synchronized int getHostConnectPort() {
        return hostConnectPort;
    }

    public synchronized NeoLinkCfg setHostConnectPort(int hostConnectPort) {
        this.hostConnectPort = requirePort(hostConnectPort, "hostConnectPort");
        return this;
    }

    public synchronized String getLocalDomainName() {
        return localDomainName;
    }

    public synchronized NeoLinkCfg setLocalDomainName(String localDomainName) {
        this.localDomainName = requireText(localDomainName, "localDomainName");
        return this;
    }

    public synchronized int getLocalPort() {
        return localPort;
    }

    public synchronized NeoLinkCfg setLocalPort(int localPort) {
        this.localPort = requirePort(localPort, "localPort");
        return this;
    }

    public synchronized String getKey() {
        return key;
    }

    public synchronized NeoLinkCfg setKey(String key) {
        this.key = requireText(key, "key");
        return this;
    }

    public synchronized String getProxyIPToLocalServer() {
        return proxyIPToLocalServer;
    }

    public synchronized NeoLinkCfg setProxyIPToLocalServer() {
        return setProxyIPToLocalServer(DEFAULT_PROXY_IP);
    }

    public synchronized NeoLinkCfg setProxyIPToLocalServer(String proxyIPToLocalServer) {
        this.proxyIPToLocalServer = nullToEmpty(proxyIPToLocalServer);
        return this;
    }

    public synchronized String getProxyIPToNeoServer() {
        return proxyIPToNeoServer;
    }

    public synchronized NeoLinkCfg setProxyIPToNeoServer() {
        return setProxyIPToNeoServer(DEFAULT_PROXY_IP);
    }

    public synchronized NeoLinkCfg setProxyIPToNeoServer(String proxyIPToNeoServer) {
        this.proxyIPToNeoServer = nullToEmpty(proxyIPToNeoServer);
        return this;
    }

    public synchronized int getHeartBeatPacketDelay() {
        return heartBeatPacketDelay;
    }

    public synchronized NeoLinkCfg setHeartBeatPacketDelay(int heartBeatPacketDelay) {
        this.heartBeatPacketDelay = requirePositive(heartBeatPacketDelay, "heartBeatPacketDelay");
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
}
