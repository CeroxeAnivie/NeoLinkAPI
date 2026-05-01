package top.ceroxe.api.neolink.exception;

/**
 * NeoProxyServer 在握手阶段拒绝访问密钥时抛出。
 *
 * <p>这里沿用 Java 常见的 {@code NoSuchKeyException} 命名，表达“服务端没有接受
 * 这个 key”。服务端原始响应会被保留，方便调用方记录真实拒绝原因。</p>
 */
public class NoSuchKeyException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String serverResponse;

    public NoSuchKeyException(String serverResponse) {
        super("NeoProxyServer rejected the access key: " + serverResponse);
        this.serverResponse = serverResponse;
    }

    public String serverResponse() {
        return serverResponse;
    }
}
