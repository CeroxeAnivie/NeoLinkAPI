package top.ceroxe.api.neolink.exception;

/**
 * NeoProxyServer {@code UnRecognizedKeyException} 的 API 侧同名映射 (server alias)。
 *
 * <p>服务端握手阶段仍返回自然语言拒绝文案，NeoLinkAPI 会先按内部 {@code LanguageData}
 * 精确识别，再抛出这个结构化异常，让调用方可以按服务端同名异常分支处理。</p>
 */
public final class UnRecognizedKeyException extends NoSuchKeyException {
    private static final long serialVersionUID = 1L;

    public UnRecognizedKeyException(String serverResponse) {
        super(serverResponse);
    }
}
