package top.ceroxe.api.neolink.network.threads;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.ceroxe.api.net.SecureServerSocket;
import top.ceroxe.api.net.SecureSocket;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TCPTransformer 集成测试
 * <p>
 * 使用真实的 SecureServerSocket 和 SecureSocket 测试数据传输
 */
@DisplayName("TCPTransformer 集成测试")
class TCPTransformerIntegrationTest {

    private static final int TEST_PORT = 45678;
    private static final int LOCAL_SERVER_PORT = 45679;
    private static Thread serverThread;
    private static SecureServerSocket secureServerSocket;
    private static volatile boolean serverRunning = false;

    @BeforeAll
    static void setUpServer() throws IOException {
        secureServerSocket = new SecureServerSocket(TEST_PORT);
        serverRunning = true;

        serverThread = Thread.ofVirtual().start(() -> {
            while (serverRunning && !secureServerSocket.isClosed()) {
                try {
                    SecureSocket clientSocket = secureServerSocket.accept();
                    Thread.ofVirtual().start(() -> handleClient(clientSocket));
                } catch (IOException e) {
                    if (serverRunning) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    @AfterAll
    static void tearDownServer() throws IOException {
        serverRunning = false;
        if (secureServerSocket != null) {
            secureServerSocket.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    private static void handleClient(SecureSocket socket) {
        try {
            while (!socket.isClosed()) {
                String msg = socket.receiveStr(1000);
                if (msg == null) break;
                socket.sendStr("ECHO: " + msg);
            }
        } catch (IOException e) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Thread startOneShotServer(
            SecureServerSocket serverSocket,
            AtomicReference<Throwable> serverError,
            SecureSocketHandler handler
    ) {
        return Thread.ofVirtual().start(() -> {
            try (SecureSocket socket = serverSocket.accept()) {
                handler.handle(socket);
            } catch (Throwable e) {
                serverError.set(e);
            }
        });
    }

    private static void failIfServerError(AtomicReference<Throwable> serverError) {
        Throwable error = serverError.get();
        if (error != null) {
            fail("SecureSocket test server failed", error);
        }
    }

    private static byte[] proxyProtocolV2Header(int payloadLength) {
        byte[] header = new byte[16 + payloadLength];
        byte[] signature = new byte[]{
                (byte) 0x0D, (byte) 0x0A, (byte) 0x0D, (byte) 0x0A,
                (byte) 0x00, (byte) 0x0D, (byte) 0x0A, (byte) 0x51,
                (byte) 0x55, (byte) 0x49, (byte) 0x54, (byte) 0x0A
        };
        System.arraycopy(signature, 0, header, 0, signature.length);
        header[12] = 0x20;
        header[13] = 0x00;
        header[14] = (byte) ((payloadLength >>> 8) & 0xFF);
        header[15] = (byte) (payloadLength & 0xFF);
        Arrays.fill(header, 16, header.length, (byte) 0x7F);
        return header;
    }

    @Test
    @DisplayName("SecureSocket 应能连接到 SecureServerSocket")
    void testSecureSocketConnection() throws IOException {
        SecureSocket client = new SecureSocket("localhost", TEST_PORT);

        assertTrue(client.isConnected());
        assertFalse(client.isClosed());

        client.close();
        assertTrue(client.isClosed());
    }

    @Test
    @DisplayName("SecureSocket 应能发送和接收字符串")
    void testSecureSocketSendReceiveString() throws IOException {
        SecureSocket client = new SecureSocket("localhost", TEST_PORT);

        client.sendStr("Hello");
        String response = client.receiveStr(2000);

        assertEquals("ECHO: Hello", response);

        client.close();
    }

    @Test
    @DisplayName("SecureSocket 应能发送和接收字节数组")
    void testSecureSocketSendReceiveBytes() throws Exception {
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = startOneShotServer(server, serverError, socket -> {
                byte[] data = socket.receiveBytes(2000);
                socket.sendBytes(data);
            });

            try (SecureSocket client = new SecureSocket("localhost", server.getLocalPort())) {
                byte[] data = new byte[]{1, 2, 3, 4, 5};
                client.sendBytes(data);

                byte[] response = client.receiveBytes(2000);
                assertArrayEquals(data, response);
            }

            serverThread.join(2000);
            failIfServerError(serverError);
        }
    }

    @Test
    @DisplayName("SecureSocket 应能发送和接收整数")
    void testSecureSocketSendReceiveInt() throws Exception {
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        try (SecureServerSocket server = new SecureServerSocket(0)) {
            Thread serverThread = startOneShotServer(server, serverError, socket -> {
                int value = socket.receiveInt(2000);
                socket.sendInt(value);
            });

            try (SecureSocket client = new SecureSocket("localhost", server.getLocalPort())) {
                client.sendInt(12345);
                assertEquals(12345, client.receiveInt(2000));
            }

            serverThread.join(2000);
            failIfServerError(serverError);
        }
    }

    @Test
    @DisplayName("SecureSocket 应能正确关闭")
    void testSecureSocketClose() throws IOException {
        SecureSocket client = new SecureSocket("localhost", TEST_PORT);

        assertFalse(client.isClosed());
        client.close();
        assertTrue(client.isClosed());
    }

    @Test
    @DisplayName("SecureSocket shutdownInput 应正常工作")
    void testSecureSocketShutdownInput() throws IOException {
        SecureSocket client = new SecureSocket("localhost", TEST_PORT);

        assertDoesNotThrow(() -> client.shutdownInput());

        client.close();
    }

    @Test
    @DisplayName("SecureSocket shutdownOutput 应正常工作")
    void testSecureSocketShutdownOutput() throws IOException {
        SecureSocket client = new SecureSocket("localhost", TEST_PORT);

        assertDoesNotThrow(() -> client.shutdownOutput());

        client.close();
    }

    @Test
    @DisplayName("SecureSocket getPort 应返回正确的端口")
    void testSecureSocketGetPort() throws IOException {
        SecureSocket client = new SecureSocket("localhost", TEST_PORT);

        assertEquals(TEST_PORT, client.getPort());

        client.close();
    }

    @Test
    @DisplayName("SecureSocket getInetAddress 应返回正确的地址")
    void testSecureSocketGetInetAddress() throws IOException {
        SecureSocket client = new SecureSocket("localhost", TEST_PORT);

        assertNotNull(client.getInetAddress());

        client.close();
    }

    @Test
    @DisplayName("TCPTransformer 应能转发数据从 SecureSocket 到普通 Socket")
    void testTCPTransformerNeoToLocal() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedData = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0);
             ServerSocket localServer = new ServerSocket(LOCAL_SERVER_PORT)) {
            Thread secureServerThread = startOneShotServer(server, serverError, socket -> {
                socket.sendBytes("TEST_DATA".getBytes());
                socket.sendBytes(null);
            });
            Thread localServerThread = Thread.ofVirtual().start(() -> {
                try {
                    Socket localClient = localServer.accept();
                    byte[] buffer = new byte[1024];
                    int len = localClient.getInputStream().read(buffer);
                    if (len > 0) {
                        receivedData.set(new String(buffer, 0, len));
                    }
                    localClient.close();
                    latch.countDown();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            SecureSocket secureClient = new SecureSocket("localhost", server.getLocalPort());
            Socket localSocket = new Socket("localhost", LOCAL_SERVER_PORT);

            TCPTransformer transformer = new TCPTransformer(secureClient, localSocket, false);
            Thread transformerThread = Thread.ofVirtual().start(transformer);

            latch.await(5, TimeUnit.SECONDS);

            assertNotNull(receivedData.get());
            assertTrue(receivedData.get().contains("TEST_DATA"));

            secureClient.close();
            localSocket.close();
            transformerThread.interrupt();
            localServerThread.interrupt();
            secureServerThread.join(2000);
            failIfServerError(serverError);
        }
    }

    @Test
    @DisplayName("TCPTransformer 应能处理 Proxy Protocol v2 头")
    void testTCPTransformerProxyProtocolV2() throws Exception {
        byte[] ppv2Header = proxyProtocolV2Header(0);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0);
             ServerSocket localServer = new ServerSocket(LOCAL_SERVER_PORT + 1)) {
            Thread secureServerThread = startOneShotServer(server, serverError, socket -> {
                socket.sendBytes(ppv2Header);
                socket.sendBytes(null);
            });
            Thread localServerThread = Thread.ofVirtual().start(() -> {
                try {
                    Socket localClient = localServer.accept();
                    byte[] buffer = new byte[1024];
                    int len = localClient.getInputStream().read(buffer);
                    if (len > 0) {
                        receivedData.set(new byte[len]);
                        System.arraycopy(buffer, 0, receivedData.get(), 0, len);
                    }
                    localClient.close();
                    latch.countDown();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            SecureSocket secureClient = new SecureSocket("localhost", server.getLocalPort());
            Socket localSocket = new Socket("localhost", LOCAL_SERVER_PORT + 1);

            TCPTransformer transformer = new TCPTransformer(secureClient, localSocket, true);
            Thread transformerThread = Thread.ofVirtual().start(transformer);

            latch.await(5, TimeUnit.SECONDS);

            assertNotNull(receivedData.get());
            assertArrayEquals(ppv2Header, receivedData.get());

            secureClient.close();
            localSocket.close();
            transformerThread.interrupt();
            localServerThread.interrupt();
            secureServerThread.join(2000);
            failIfServerError(serverError);
        }
    }

    @Test
    @DisplayName("TCPTransformer 应能跨 SecureSocket 帧剥离完整 Proxy Protocol v2 头")
    void testTCPTransformerStripsSplitProxyProtocolV2Header() throws Exception {
        byte[] ppv2Header = proxyProtocolV2Header(12);
        byte[] firstPart = Arrays.copyOfRange(ppv2Header, 0, 7);
        byte[] secondPart = Arrays.copyOfRange(ppv2Header, 7, ppv2Header.length);
        byte[] payload = "REAL_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0);
             ServerSocket localServer = new ServerSocket(0)) {
            Thread secureServerThread = startOneShotServer(server, serverError, socket -> {
                socket.sendBytes(firstPart);
                socket.sendBytes(secondPart);
                socket.sendBytes(payload);
                socket.sendBytes(null);
            });
            Thread localServerThread = Thread.ofVirtual().start(() -> {
                try {
                    Socket localClient = localServer.accept();
                    byte[] buffer = new byte[1024];
                    int len = localClient.getInputStream().read(buffer);
                    if (len > 0) {
                        receivedData.set(Arrays.copyOf(buffer, len));
                    }
                    localClient.close();
                    latch.countDown();
                } catch (IOException e) {
                    serverError.compareAndSet(null, e);
                }
            });

            SecureSocket secureClient = new SecureSocket("localhost", server.getLocalPort());
            Socket localSocket = new Socket("localhost", localServer.getLocalPort());
            TCPTransformer transformer = new TCPTransformer(secureClient, localSocket, false);
            Thread transformerThread = Thread.ofVirtual().start(transformer);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertArrayEquals(payload, receivedData.get());

            secureClient.close();
            localSocket.close();
            transformerThread.interrupt();
            localServerThread.interrupt();
            secureServerThread.join(2000);
            failIfServerError(serverError);
        }
    }

    @Test
    @DisplayName("TCPTransformer 禁用 Proxy Protocol v2 时不应转发残缺 PPv2 头")
    void testTCPTransformerDropsIncompleteProxyProtocolV2Header() throws Exception {
        byte[] partialHeader = Arrays.copyOfRange(proxyProtocolV2Header(12), 0, 15);
        CountDownLatch eofReached = new CountDownLatch(1);
        AtomicReference<Integer> receivedBytes = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket server = new SecureServerSocket(0);
             ServerSocket localServer = new ServerSocket(0)) {
            Thread secureServerThread = startOneShotServer(server, serverError, socket -> {
                socket.sendBytes(partialHeader);
                socket.sendBytes(null);
            });
            Thread localServerThread = Thread.ofVirtual().start(() -> {
                try (Socket localClient = localServer.accept()) {
                    receivedBytes.set(localClient.getInputStream().read());
                    eofReached.countDown();
                } catch (IOException e) {
                    serverError.compareAndSet(null, e);
                }
            });

            SecureSocket secureClient = new SecureSocket("localhost", server.getLocalPort());
            Socket localSocket = new Socket("localhost", localServer.getLocalPort());
            TCPTransformer transformer = new TCPTransformer(secureClient, localSocket, false);
            Thread transformerThread = Thread.ofVirtual().start(transformer);

            assertTrue(eofReached.await(5, TimeUnit.SECONDS));
            assertEquals(-1, receivedBytes.get());

            secureClient.close();
            localSocket.close();
            transformerThread.interrupt();
            localServerThread.interrupt();
            secureServerThread.join(2000);
            failIfServerError(serverError);
        }
    }

    @FunctionalInterface
    private interface SecureSocketHandler {
        void handle(SecureSocket socket) throws Exception;
    }
}
