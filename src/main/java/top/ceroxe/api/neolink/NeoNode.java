package top.ceroxe.api.neolink;

import java.util.Objects;

/**
 * 不可变的 NKM 公共节点元数据。
 *
 * <p>NKM 节点除了连接端点之外，还携带展示名、图标等 UI 元数据。{@link NeoLinkCfg}
 * 刻意只保存隧道启动配置，因此这里保留完整的节点列表载荷，并暴露
 * {@link #toCfg(String, int)}，方便调用方基于选定节点直接启动隧道。</p>
 */
public final class NeoNode {
    private final String name;
    private final String realId;
    private final String address;
    private final String iconSvg;
    private final int hookPort;
    private final int connectPort;

    /**
     * 创建一个不可变的 NKM 节点对象。
     *
     * @param name        来自 NKM 的展示名
     * @param realId      稳定的 NKM 节点标识；手工构造时可以为 {@code null}，但 {@link NodeFetcher} 需要它
     * @param address     NeoProxyServer 的域名或 IP
     * @param iconSvg     来自 NKM 的可选 SVG 图标内容
     * @param hookPort    NeoProxyServer 控制端口
     * @param connectPort NeoProxyServer 传输端口
     */
    public NeoNode(String name, String realId, String address, String iconSvg, int hookPort, int connectPort) {
        this.name = requireText(name, "name");
        this.realId = normalizeOptionalText(realId);
        this.address = requireText(address, "address");
        this.iconSvg = normalizeOptionalText(iconSvg);
        this.hookPort = requirePort(hookPort, "hookPort");
        this.connectPort = requirePort(connectPort, "connectPort");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }
        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static int requirePort(int value, String fieldName) {
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 65535.");
        }
        return value;
    }

    /**
     * 将这个公共节点元数据转换为完整的隧道配置。
     *
     * <p>NKM 只提供公开的远端端点。访问密钥和本地下游端口属于调用方私有数据，因此
     * 这里要求在转换时一次性传入，而不是返回一个半成品配置让后续启动阶段再失败。</p>
     *
     * @param key       所选 NeoProxyServer 节点的访问密钥
     * @param localPort 本地下游服务端口
     * @return 基于当前节点和调用方私有配置构造出的 NeoLink 配置
     * @throws IllegalArgumentException 当 {@code key} 为空白或 {@code localPort} 不在 1..65535 时抛出
     */
    public NeoLinkCfg toCfg(String key, int localPort) {
        return new NeoLinkCfg(address, hookPort, connectPort, key, localPort);
    }

    public String getName() {
        return name;
    }

    public String getRealId() {
        return realId;
    }

    public String getAddress() {
        return address;
    }

    public String getIconSvg() {
        return iconSvg;
    }

    public int getHookPort() {
        return hookPort;
    }

    public int getConnectPort() {
        return connectPort;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NeoNode neoNode)) {
            return false;
        }
        return hookPort == neoNode.hookPort
                && connectPort == neoNode.connectPort
                && name.equals(neoNode.name)
                && Objects.equals(realId, neoNode.realId)
                && address.equals(neoNode.address)
                && Objects.equals(iconSvg, neoNode.iconSvg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, realId, address, iconSvg, hookPort, connectPort);
    }

    @Override
    public String toString() {
        return "NeoNode{"
                + "name='" + name + '\''
                + ", realId='" + realId + '\''
                + ", address='" + address + '\''
                + ", iconSvg=" + (iconSvg == null ? "null" : "<svg>")
                + ", hookPort=" + hookPort
                + ", connectPort=" + connectPort
                + '}';
    }
}
