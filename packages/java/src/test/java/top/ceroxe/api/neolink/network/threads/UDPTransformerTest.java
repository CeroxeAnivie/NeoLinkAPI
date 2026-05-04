package top.ceroxe.api.neolink.network.threads;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.ceroxe.api.net.SecureSocket;

import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
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
}
