package top.ceroxe.api.neolink;

/**
 * NeoProxyServer 语言协议镜像 (LanguageData mirror)。
 *
 * <p>NeoProxyServer 当前在握手阶段用自然语言文本表达成功和失败状态，API 必须先按
 * 服务端 {@code LanguageData} 的稳定文本还原成结构化异常，再交给调用方处理。这个类
 * 是包级可见 (package-private) 的内部协议表，不属于对外 API，避免调用方依赖服务端
 * 文案细节。</p>
 */
final class LanguageData {
    static final LanguageData ENGLISH = new LanguageData(
            "Connection build up successfully",
            "Connection rejected: Port occupied by another node or limit reached.",
            "This port is already in use. Please try with a different node.",
            "Access denied , force exiting...",
            "This key have no network flow left ! Force exiting...",
            "Unsupported version ! It should be :",
            "Key ",
            "The key ",
            " are out of date."
    );

    static final LanguageData CHINESE = new LanguageData(
            "服务器连接成功",
            "连接被拒绝：该端口已被其他节点占用，或已达到最大允许连接数。",
            "这个端口已经被占用了，请你更换节点重试。",
            "密钥错误，强制退出。。。",
            "这个密钥已经没有流量了，强制退出。。。",
            "不受支持的版本，应该为",
            "密钥 ",
            "这个密钥 ",
            " 已经过期了。"
    );

    private static final LanguageData[] ALL = {ENGLISH, CHINESE};

    final String connectionBuildUpSuccessfully;
    final String remotePortOccupied;
    final String portAlreadyInUse;
    final String accessDeniedForceExiting;
    final String noNetworkFlowLeft;
    final String unsupportedVersionPrefix;
    final String keyPrefix;
    final String keyAltPrefix;
    final String keyOutdatedSuffix;

    private LanguageData(
            String connectionBuildUpSuccessfully,
            String remotePortOccupied,
            String portAlreadyInUse,
            String accessDeniedForceExiting,
            String noNetworkFlowLeft,
            String unsupportedVersionPrefix,
            String keyPrefix,
            String keyAltPrefix,
            String keyOutdatedSuffix
    ) {
        this.connectionBuildUpSuccessfully = connectionBuildUpSuccessfully;
        this.remotePortOccupied = remotePortOccupied;
        this.portAlreadyInUse = portAlreadyInUse;
        this.accessDeniedForceExiting = accessDeniedForceExiting;
        this.noNetworkFlowLeft = noNetworkFlowLeft;
        this.unsupportedVersionPrefix = unsupportedVersionPrefix;
        this.keyPrefix = keyPrefix;
        this.keyAltPrefix = keyAltPrefix;
        this.keyOutdatedSuffix = keyOutdatedSuffix;
    }

    static LanguageData[] all() {
        return ALL.clone();
    }
}
