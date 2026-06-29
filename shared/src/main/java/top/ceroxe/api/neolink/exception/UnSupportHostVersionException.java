package top.ceroxe.api.neolink.exception;

/**
 * NeoProxyServer {@code UnSupportHostVersionException} 的 API 侧同名映射 (server alias)。
 */
public final class UnSupportHostVersionException extends UnsupportedVersionException {
    private static final long serialVersionUID = 1L;

    public UnSupportHostVersionException(String serverResponse) {
        super(serverResponse);
    }
}
