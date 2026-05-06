package top.ceroxe.api.neolink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.ceroxe.api.neolink.exception.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Handshake protocol mirror")
class HandshakeProtocolMirrorTest {
    private static Exception classify(String response) throws Exception {
        Method method = declaredMethod(NeoLinkAPI.class, "classifyStartupHandshakeFailure", String.class);
        method.setAccessible(true);
        try {
            return (Exception) method.invoke(null, response);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static Method declaredMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Test
    @DisplayName("中英文握手失败文案应被稳定分类")
    void startupFailureTextsStayClassifiable() throws Exception {
        assertInstanceOf(
                PortOccupiedException.class,
                classify("Connection rejected: Port occupied by another node or limit reached.")
        );
        assertInstanceOf(
                NoSuchKeyException.class,
                classify("密钥错误，强制退出。。。")
        );
        assertInstanceOf(
                NoMoreNetworkFlowException.class,
                classify("This key have no network flow left ! Force exiting...")
        );
        assertInstanceOf(
                OutDatedKeyException.class,
                classify("这个密钥 paid-key-01 已经过期了。")
        );
        assertInstanceOf(
                UnSupportHostVersionException.class,
                classify("不受支持的版本，应该为:7.1.12")
        );
        assertNull(classify("Connection build up successfully"));
    }

    @Test
    @DisplayName("中英文隧道地址提示应继续提取纯地址")
    void tunnelAddressMessagesStayParsable() {
        assertEquals(
                "edge.example.test:45678",
                NeoLinkAPI.parseTunAddrMessage("Use the address: edge.example.test:45678 to start up connections.")
        );
        assertEquals(
                "cn.example.test:45679",
                NeoLinkAPI.parseTunAddrMessage("使用链接地址： cn.example.test:45679 来从公网连接。")
        );
    }
}
