package top.ceroxe.api.neolink.exception;

/**
 * NeoProxyServer 在握手阶段明确拒绝当前客户端版本时抛出。
 */
public final class UnsupportedVersionException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String serverResponse;

    public UnsupportedVersionException(String serverResponse) {
        super("NeoProxyServer does not support this NeoLinkAPI version: " + serverResponse);
        this.serverResponse = serverResponse;
    }

    public String serverResponse() {
        return serverResponse;
    }
}
