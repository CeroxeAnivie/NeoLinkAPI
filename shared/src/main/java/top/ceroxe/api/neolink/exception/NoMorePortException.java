package top.ceroxe.api.neolink.exception;

import java.io.IOException;

/**
 * NeoProxyServer {@code NoMorePortException} 的 API 侧同名映射 (server alias)。
 */
public final class NoMorePortException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String serverResponse;

    public NoMorePortException(String serverResponse) {
        super("NeoProxyServer cannot allocate the requested port: " + serverResponse);
        this.serverResponse = serverResponse;
    }

    public String serverResponse() {
        return serverResponse;
    }
}
