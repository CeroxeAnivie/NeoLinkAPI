package top.ceroxe.api.neolink.exception;

import java.io.IOException;

/**
 * NeoProxyServer {@code PortOccupiedException} 的 API 侧同名映射 (server alias)。
 */
public final class PortOccupiedException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String serverResponse;

    public PortOccupiedException(String serverResponse) {
        super("NeoProxyServer rejected the requested node because the remote port quota is occupied: "
                + serverResponse);
        this.serverResponse = serverResponse;
    }

    public String serverResponse() {
        return serverResponse;
    }
}
