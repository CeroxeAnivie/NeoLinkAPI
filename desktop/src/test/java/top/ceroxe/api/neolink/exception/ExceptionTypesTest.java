package top.ceroxe.api.neolink.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Typed tunnel exceptions")
class ExceptionTypesTest {
    @Test
    @DisplayName("unsupported version exception preserves server response")
    void unsupportedVersionKeepsServerResponse() {
        UnsupportedVersionException exception = new UnsupportedVersionException("unsupported:6.0.1|7.0.0");

        assertEquals("unsupported:6.0.1|7.0.0", exception.serverResponse());
        assertTrue(exception.getMessage().contains("does not support"));
    }

    @Test
    @DisplayName("NoSuchKeyException 保留服务端原始响应")
    void noSuchKeyKeepsServerResponse() {
        NoSuchKeyException exception = new NoSuchKeyException("access code denied");

        assertEquals("access code denied", exception.serverResponse());
        assertTrue(exception.getMessage().contains("access key"));
    }

    @Test
    @DisplayName("NoMoreNetworkFlowException 保留流量耗尽原因")
    void noMoreNetworkFlowKeepsServerResponse() {
        NoMoreNetworkFlowException exception = new NoMoreNetworkFlowException("exitNoFlow");

        assertEquals("exitNoFlow", exception.serverResponse());
        assertTrue(exception.getMessage().contains("no network flow remains"));
    }
}
