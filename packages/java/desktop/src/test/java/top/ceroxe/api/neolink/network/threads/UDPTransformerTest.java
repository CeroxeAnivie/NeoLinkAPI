package top.ceroxe.api.neolink.network.threads;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.ceroxe.api.net.SecureServerSocket;
import top.ceroxe.api.net.SecureSocket;

import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UDPTransformer behavior")
class UDPTransformerTest {
    private static byte[] invokeSerialize(UDPTransformer transformer, DatagramPacket packet) throws Exception {
        var method = UDPTransformer.class.getDeclaredMethod("serializeDatagramPacket", DatagramPacket.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(transformer, packet);
    }

    @Test
    @DisplayName("IPv4 packets serialize and deserialize consistently")
    void serializeAndDeserializeIpv4Packet() throws Exception {
        byte[] payload = "Hello UDP".getBytes(StandardCharsets.UTF_8);
        InetAddress address = InetAddress.getByName("127.0.0.1");
        DatagramPacket originalPacket = new DatagramPacket(payload, payload.length, address, 12345);

        UDPTransformer transformer = new UDPTransformer((SecureSocket) null, (DatagramSocket) null);
        byte[] serialized = invokeSerialize(transformer, originalPacket);
        DatagramPacket deserialized = UDPTransformer.deserializeToDatagramPacket(serialized);

        assertNotNull(deserialized);
        assertEquals(12345, deserialized.getPort());
        assertArrayEquals(payload, deserialized.getData());
    }

    @Test
    @DisplayName("IPv6 packets serialize and deserialize consistently")
    void serializeAndDeserializeIpv6Packet() throws Exception {
        byte[] payload = "IPv6".getBytes(StandardCharsets.UTF_8);
        InetAddress address = InetAddress.getByName("::1");
        DatagramPacket originalPacket = new DatagramPacket(payload, payload.length, address, 54321);

        UDPTransformer transformer = new UDPTransformer((SecureSocket) null, (DatagramSocket) null);
        byte[] serialized = invokeSerialize(transformer, originalPacket);
        DatagramPacket deserialized = UDPTransformer.deserializeToDatagramPacket(serialized);

        assertNotNull(deserialized);
        assertEquals(54321, deserialized.getPort());
        assertArrayEquals(payload, deserialized.getData());
    }

    @Test
    @DisplayName("Malformed packets report diagnostics to the provided debug sink")
    void malformedPacketUsesProvidedDebugSink() {
        AtomicReference<String> debugMessage = new AtomicReference<>();
        AtomicReference<Throwable> debugCause = new AtomicReference<>();

        DatagramPacket packet = UDPTransformer.deserializeToDatagramPacket(
                new byte[20],
                true,
                (message, cause) -> {
                    debugMessage.set(message);
                    debugCause.set(cause);
                }
        );

        assertNull(packet);
        assertNotNull(debugMessage.get());
        assertNull(debugCause.get());
    }

    @Test
    @DisplayName("Malformed packets stay silent when debug is disabled")
    void malformedPacketRespectsDisabledDebug() {
        AtomicInteger debugCalls = new AtomicInteger();

        DatagramPacket packet = UDPTransformer.deserializeToDatagramPacket(
                new byte[20],
                false,
                (message, cause) -> debugCalls.incrementAndGet()
        );

        assertNull(packet);
        assertEquals(0, debugCalls.get());
    }

    @Test
    @DisplayName("Constructor fails fast for an unresolvable local host")
    void constructorRejectsUnresolvableLocalHost() {
        assertThrows(
                UncheckedIOException.class,
                () -> new UDPTransformer((SecureSocket) null, (DatagramSocket) null, "nonexistent.invalid.test", 1)
        );
    }

    @Test
    @DisplayName("run tolerates null sockets")
    void runWithNullSocketsDoesNotThrow() {
        UDPTransformer neoToLocal = new UDPTransformer((SecureSocket) null, (DatagramSocket) null);
        UDPTransformer localToNeo = new UDPTransformer((DatagramSocket) null, (SecureSocket) null);

        assertDoesNotThrow(neoToLocal::run);
        assertDoesNotThrow(localToNeo::run);
    }

    @Test
    @DisplayName("Neo -> Local telemetry counts delivered UDP payload bytes")
    void neoToLocalTelemetryCountsDeliveredPayloadBytes() throws Exception {
        byte[] payload = "UDP_NEO_TO_LOCAL".getBytes(StandardCharsets.UTF_8);
        AtomicLong trafficBytes = new AtomicLong();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<byte[]> receivedPayload = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket secureServer = new SecureServerSocket(0);
             DatagramSocket localTarget = new DatagramSocket(0);
             DatagramSocket transformerSocket = new DatagramSocket(0)) {
            Thread secureServerThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = secureServer.accept()) {
                    socket.sendBytes(serializeUdpPacket(payload, InetAddress.getByName("127.0.0.1"), localTarget.getLocalPort()));
                    socket.sendBytes(null);
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            Thread localTargetThread = Thread.ofVirtual().start(() -> {
                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    localTarget.receive(packet);
                    receivedPayload.set(copyPayload(packet));
                    received.countDown();
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            try (SecureSocket secureClient = new SecureSocket("localhost", secureServer.getLocalPort())) {
                Thread transformerThread = Thread.ofVirtual().start(new UDPTransformer(
                        secureClient,
                        transformerSocket,
                        "localhost",
                        localTarget.getLocalPort(),
                        false,
                        (message, cause) -> {
                        },
                        trafficBytes::addAndGet
                ));

                assertTrue(received.await(5, TimeUnit.SECONDS));
                assertArrayEquals(payload, receivedPayload.get());
                assertEquals(payload.length, trafficBytes.get());

                transformerSocket.close();
                transformerThread.join(2000);
            }

            secureServerThread.join(2000);
            localTargetThread.join(2000);
            failIfThreadError(serverError);
        }
    }

    @Test
    @DisplayName("Local -> Neo telemetry counts sent UDP payload bytes")
    void localToNeoTelemetryCountsSentPayloadBytes() throws Exception {
        byte[] payload = "UDP_LOCAL_TO_NEO".getBytes(StandardCharsets.UTF_8);
        AtomicLong trafficBytes = new AtomicLong();
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<byte[]> receivedPayload = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket secureServer = new SecureServerSocket(0);
             DatagramSocket localReceiver = new DatagramSocket(0);
             DatagramSocket localSender = new DatagramSocket()) {
            Thread secureServerThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = secureServer.accept()) {
                    byte[] serialized = socket.receiveBytes(2000);
                    DatagramPacket packet = UDPTransformer.deserializeToDatagramPacket(serialized);
                    receivedPayload.set(packet == null ? null : copyPayload(packet));
                    received.countDown();
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            try (SecureSocket secureClient = new SecureSocket("localhost", secureServer.getLocalPort())) {
                Thread transformerThread = Thread.ofVirtual().start(new UDPTransformer(
                        localReceiver,
                        secureClient,
                        "localhost",
                        1,
                        false,
                        (message, cause) -> {
                        },
                        trafficBytes::addAndGet
                ));

                DatagramPacket packet = new DatagramPacket(
                        payload,
                        payload.length,
                        InetAddress.getByName("127.0.0.1"),
                        localReceiver.getLocalPort()
                );
                localSender.send(packet);

                assertTrue(received.await(5, TimeUnit.SECONDS));
                assertArrayEquals(payload, receivedPayload.get());
                assertEquals(payload.length, trafficBytes.get());

                localReceiver.close();
                transformerThread.join(2000);
            }

            secureServerThread.join(2000);
            failIfThreadError(serverError);
        }
    }

    @Test
    @DisplayName("Telemetry callback failures do not interrupt UDP forwarding")
    void telemetryFailureDoesNotInterruptUdpForwarding() throws Exception {
        byte[] payload = "UDP_THROWING_SINK".getBytes(StandardCharsets.UTF_8);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<byte[]> receivedPayload = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        try (SecureServerSocket secureServer = new SecureServerSocket(0);
             DatagramSocket localReceiver = new DatagramSocket(0);
             DatagramSocket localSender = new DatagramSocket()) {
            Thread secureServerThread = Thread.ofVirtual().start(() -> {
                try (SecureSocket socket = secureServer.accept()) {
                    byte[] serialized = socket.receiveBytes(2000);
                    DatagramPacket packet = UDPTransformer.deserializeToDatagramPacket(serialized);
                    receivedPayload.set(packet == null ? null : copyPayload(packet));
                    received.countDown();
                } catch (Throwable e) {
                    serverError.compareAndSet(null, e);
                }
            });

            try (SecureSocket secureClient = new SecureSocket("localhost", secureServer.getLocalPort())) {
                Thread transformerThread = Thread.ofVirtual().start(new UDPTransformer(
                        localReceiver,
                        secureClient,
                        "localhost",
                        1,
                        false,
                        (message, cause) -> {
                        },
                        bytes -> {
                            throw new IllegalStateException("telemetry sink failed");
                        }
                ));

                localSender.send(new DatagramPacket(
                        payload,
                        payload.length,
                        InetAddress.getByName("127.0.0.1"),
                        localReceiver.getLocalPort()
                ));

                assertTrue(received.await(5, TimeUnit.SECONDS));
                assertArrayEquals(payload, receivedPayload.get());

                localReceiver.close();
                transformerThread.join(2000);
            }

            secureServerThread.join(2000);
            failIfThreadError(serverError);
        }
    }

    private static byte[] serializeUdpPacket(byte[] data, InetAddress address, int port) throws Exception {
        UDPTransformer transformer = new UDPTransformer((SecureSocket) null, (DatagramSocket) null);
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        return invokeSerialize(transformer, packet);
    }

    private static byte[] copyPayload(DatagramPacket packet) {
        byte[] payload = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), payload, 0, packet.getLength());
        return payload;
    }

    private static void failIfThreadError(AtomicReference<Throwable> serverError) {
        Throwable error = serverError.get();
        if (error != null) {
            fail("UDP transformer test thread failed", error);
        }
    }
}
