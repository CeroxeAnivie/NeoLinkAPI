package top.ceroxe.api.neolink.exception;

/**
 * NeoProxyServer {@code OutDatedKeyException} 的 API 侧同名映射 (server alias)。
 *
 * <p>它仍然继承 {@link NoSuchKeyException}，保证旧调用方捕获宽泛密钥异常的行为不变；
 * 新调用方则可以按服务端同名异常做更精确的业务处理。</p>
 */
public final class OutDatedKeyException extends NoSuchKeyException {
    private static final long serialVersionUID = 1L;

    public OutDatedKeyException(String serverResponse) {
        super(serverResponse);
    }
}
