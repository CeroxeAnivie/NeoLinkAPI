package top.ceroxe.api.neolink;

import java.util.Objects;

/**
 * Immutable NKM public-node metadata.
 *
 * <p>NKM nodes carry UI metadata such as display name and icon beside the
 * connection endpoint. {@link NeoLinkCfg} intentionally stores only tunnel
 * startup configuration, so this type keeps the complete node-list payload and
 * exposes {@link #toCfg()} for callers that want to start a tunnel from a
 * selected node.</p>
 */
public final class NeoNode {
    private final String name;
    private final String realId;
    private final String address;
    private final String iconSvg;
    private final int hookPort;
    private final int connectPort;

    /**
     * Creates an immutable NKM node.
     *
     * @param name user-facing display name from NKM
     * @param realId stable NKM node identity; may be {@code null} for manually
     *               constructed nodes, but {@link NodeFetcher} requires it
     * @param address NeoProxyServer domain or IP
     * @param iconSvg optional SVG icon content from NKM
     * @param hookPort NeoProxyServer hook/control port
     * @param connectPort NeoProxyServer transfer port
     */
    public NeoNode(String name, String realId, String address, String iconSvg, int hookPort, int connectPort) {
        this.name = requireText(name, "name");
        this.realId = normalizeOptionalText(realId);
        this.address = requireText(address, "address");
        this.iconSvg = normalizeOptionalText(iconSvg);
        this.hookPort = requirePort(hookPort, "hookPort");
        this.connectPort = requirePort(connectPort, "connectPort");
    }

    /**
     * Converts this public-node metadata into a tunnel configuration skeleton.
     *
     * <p>The returned config intentionally contains only the remote endpoint.
     * Access key and local downstream port are caller-owned private values and
     * must be set with {@link NeoLinkCfg#setKey(String)} and
     * {@link NeoLinkCfg#setLocalPort(int)} before {@link NeoLinkAPI#start()}.</p>
     *
     * @return a NeoLink configuration built from this node's remote endpoint
     */
    public NeoLinkCfg toCfg() {
        return NeoLinkCfg.fromRemoteNode(address, hookPort, connectPort);
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
}
