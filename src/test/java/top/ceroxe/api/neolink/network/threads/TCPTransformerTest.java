package top.ceroxe.api.neolink.network.threads;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("TCPTransformer behavior")
class TCPTransformerTest {
    @Test
    @DisplayName("run tolerates null sockets")
    void runWithNullSocketsDoesNotThrow() {
        TCPTransformer neoToLocal = new TCPTransformer((SecureSocket) null, (Socket) null, false);
        TCPTransformer localToNeo = new TCPTransformer((Socket) null, (SecureSocket) null, false);

        assertDoesNotThrow(neoToLocal::run);
        assertDoesNotThrow(localToNeo::run);
    }

    @Test
    @DisplayName("Proxy Protocol v2 header is stripped when disabled")
    void stripsProxyProtocolHeaderWhenDisabled() throws Exception {
        byte[] payload = "REAL_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        byte[] frame = concat(proxyProtocolV2Header(0), payload);

        byte[] received = forwardNeoToLocal(frame, false);

        assertArrayEquals(payload, received);
    }

    @Test
    @DisplayName("Proxy Protocol v2 header is forwarded when enabled")
    void forwardsProxyProtocolHeaderWhenEnabled() throws Exception {
        byte[] header = proxyProtocolV2Header(0);
        byte[] payload = "REAL_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        byte[] frame = concat(header, payload);

        byte[] received = forwardNeoToLocal(frame, true);

        assertArrayEquals(frame, received);
    }

    @Test
    @DisplayName("Incomplete Proxy Protocol v2 header is dropped when disabled")
    void dropsIncompleteProxyProtocolHeaderWhenDisabled() throws Exception {
        byte[] incompleteHeader = Arrays.copyOf(proxyProtocolV2Header(12), 15);

        byte[] received = forwardNeoToLocal(incompleteHeader, false);

        assertEquals(0, received.length);
    }

    private static byte[] forwardNeoToLocal(byte[] frame, boolean enableProxyProtocol) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<byte[]> received = new AtomicReference<>(new byte[0]);
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket secureServer = new SecureServerSocket(0);
             ServerSocket localServer = new ServerSocket(0)) {
            Thread secureServerThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = secureServer.accept()) {
                    socket.sendBytes(frame);
                    socket.sendBytes(null);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            Thread localServerThread = Thread.ofVirtual().start(() -> {
                try (Socket localClient = localServer.accept()) {
                    received.set(localClient.getInputStream().readAllBytes());
                    latch.countDown();
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            try (SecureSocket secureClient = new SecureSocket("localhost", secureServer.getLocalPort());
                 Socket localSocket = new Socket("localhost", localServer.getLocalPort())) {
                Thread transformerThread = Thread.ofVirtual().start(
                        new TCPTransformer(secureClient, localSocket, enableProxyProtocol)
                );

                assertTrue(latch.await(5, TimeUnit.SECONDS));
                transformerThread.join(2000);
            }

            secureServerThread.join(2000);
            localServerThread.join(2000);
            failIfServerError(serverError);
            return received.get();
        }
    }

    private static void failIfServerError(AtomicReference<Throwable> serverError) {
        Throwable error = serverError.get();
        if (error != null) {
            fail("TCP transformer test server failed", error);
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

    private static byte[] concat(byte[] first, byte[] second) throws IOException {
        byte[] merged = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        return merged;
    }
}
