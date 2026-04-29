package top.ceroxe.api.neolink.exception;

/**
 * NeoProxyServer 因访问密钥的剩余流量耗尽而终止连接时抛出。
 */
public final class NoMoreNetworkFlowException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String serverResponse;

    public NoMoreNetworkFlowException() {
        this("NeoProxyServer reported that no network flow remains.");
    }

    public NoMoreNetworkFlowException(String serverResponse) {
        super("NeoProxyServer terminated the tunnel because no network flow remains: " + serverResponse);
        this.serverResponse = serverResponse;
    }

    public String serverResponse() {
        return serverResponse;
    }
}
