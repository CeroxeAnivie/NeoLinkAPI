package top.ceroxe.api.neolink.network.threads;

import top.ceroxe.api.net.SecureSocket;
import top.ceroxe.api.neolink.util.Debugger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;

import static top.ceroxe.api.neolink.network.InternetOperator.close;

/**
 * UDP 数据转发器。
 *
 * 核心职责：
 * 1. 在本地服务和 Neo 服务器之间双向转发 UDP 数据
 * 2. 使用 ByteBuffer 实现高效的字节操作
 * 3. 通过复用实例缓冲区减少 GC 压力
 *
 * 设计特点：
 * - 双向转发：支持 Neo -> 本地、本地 -> Neo 两种模式
 * - ByteBuffer 优化：使用堆上缓冲区提升 I/O 性能
 * - 缓冲区复用：每个实例使用独立缓冲区，避免频繁分配内存
 * - 优雅关闭：支持中断信号，确保资源正确释放
 *
 * 性能优化：
 * - 使用 65535 字节缓冲区，支持最大 UDP 报文
 * - 通过 ByteBuffer 直接操作字节数组，减少拷贝开销
 *
 * @author NeoProxy Team
 * @since 5.0.0
 */
public class UDPTransformer implements Runnable {
    public static final int MODE_NEO_TO_LOCAL = 0;
    public static final int MODE_LOCAL_TO_NEO = 1;
    private static final int MAGIC = 0xDEADBEEF;
    private static final int SERIALIZED_HEADER_FIXED_LENGTH = 4 + 4 + 4 + 2;
    private static final int IPV4_LENGTH = 4;
    private static final int IPV6_LENGTH = 16;
    private static final int BUFFER_LENGTH = 65535;

    private final DatagramSocket plainSocket;
    private final SecureSocket secureSocket;
    private final int mode;
    private final String localHost;
    private final InetAddress localAddress;
    private final int localPort;
    private final boolean debugEnabled;
    private final BiConsumer<String, Throwable> debugSink;

    // 每条 UDP 转发链路独占接收缓冲区，避免多连接并发时复用同一数组。
    private final byte[] receiveBuffer = new byte[BUFFER_LENGTH];

    // 序列化缓冲区绑定在实例上，既减少分配，也避免不同 UDP 会话互相覆盖数据。
    private final ByteBuffer serializationBuffer = ByteBuffer.allocate(
            SERIALIZED_HEADER_FIXED_LENGTH + IPV6_LENGTH + BUFFER_LENGTH
    );

    /**
     * 构造函数：用于从 Neo 服务器接收数据并转发到本地服务。
     */
    public UDPTransformer(SecureSocket secureSender, DatagramSocket localReceiver) {
        this(secureSender, localReceiver, "localhost", 1);
    }

    public UDPTransformer(SecureSocket secureSender, DatagramSocket localReceiver, String localHost, int localPort) {
        this(secureSender, localReceiver, localHost, localPort, Debugger.isEnabled());
    }

    public UDPTransformer(
            SecureSocket secureSender,
            DatagramSocket localReceiver,
            String localHost,
            int localPort,
            boolean debugEnabled
    ) {
        this(secureSender, localReceiver, localHost, localPort, debugEnabled, UDPTransformer::defaultDebugSink);
    }

    public UDPTransformer(
            SecureSocket secureSender,
            DatagramSocket localReceiver,
            String localHost,
            int localPort,
            boolean debugEnabled,
            BiConsumer<String, Throwable> debugSink
    ) {
        this.secureSocket = secureSender;
        this.plainSocket = localReceiver;
        this.mode = MODE_NEO_TO_LOCAL;
        this.localHost = localHost;
        this.localAddress = resolveLocalAddress(localHost);
        this.localPort = localPort;
        this.debugEnabled = debugEnabled;
        this.debugSink = Objects.requireNonNull(debugSink, "debugSink");
    }

    /**
     * 构造函数：用于从本地服务接收数据并转发到 Neo 服务器。
     */
    public UDPTransformer(DatagramSocket localSender, SecureSocket secureReceiver) {
        this(localSender, secureReceiver, "localhost", 1);
    }

    public UDPTransformer(DatagramSocket localSender, SecureSocket secureReceiver, String localHost, int localPort) {
        this(localSender, secureReceiver, localHost, localPort, Debugger.isEnabled());
    }

    public UDPTransformer(
            DatagramSocket localSender,
            SecureSocket secureReceiver,
            String localHost,
            int localPort,
            boolean debugEnabled
    ) {
        this(localSender, secureReceiver, localHost, localPort, debugEnabled, UDPTransformer::defaultDebugSink);
    }

    public UDPTransformer(
            DatagramSocket localSender,
            SecureSocket secureReceiver,
            String localHost,
            int localPort,
            boolean debugEnabled,
            BiConsumer<String, Throwable> debugSink
    ) {
        this.plainSocket = localSender;
        this.secureSocket = secureReceiver;
        this.mode = MODE_LOCAL_TO_NEO;
        this.localHost = localHost;
        this.localAddress = resolveLocalAddress(localHost);
        this.localPort = localPort;
        this.debugEnabled = debugEnabled;
        this.debugSink = Objects.requireNonNull(debugSink, "debugSink");
    }

    /**
     * 这个方法可以保持为静态，因为它不依赖实例状态。
     */
    public static DatagramPacket deserializeToDatagramPacket(byte[] serializedData) {
        return deserializeToDatagramPacket(serializedData, true, UDPTransformer::defaultDebugSink);
    }

    static DatagramPacket deserializeToDatagramPacket(
            byte[] serializedData,
            boolean debugEnabled,
            BiConsumer<String, Throwable> debugSink
    ) {
        if (serializedData == null || serializedData.length < SERIALIZED_HEADER_FIXED_LENGTH + IPV4_LENGTH) {
            return invalidSerializedPacket("Serialized UDP packet is too short", debugEnabled, debugSink);
        }

        ByteBuffer buffer = ByteBuffer.wrap(serializedData);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int magic = buffer.getInt();
        if (magic != MAGIC) {
            return invalidSerializedPacket("Invalid magic number in serialized data", debugEnabled, debugSink);
        }

        int dataLen = buffer.getInt();
        int ipLen = buffer.getInt();
        if (dataLen < 0 || dataLen > BUFFER_LENGTH) {
            return invalidSerializedPacket("Invalid UDP data length: " + dataLen, debugEnabled, debugSink);
        }
        if (ipLen != IPV4_LENGTH && ipLen != IPV6_LENGTH) {
            return invalidSerializedPacket("Invalid IP address length: " + ipLen, debugEnabled, debugSink);
        }

        int expectedLength = SERIALIZED_HEADER_FIXED_LENGTH + ipLen + dataLen;
        if (serializedData.length != expectedLength) {
            return invalidSerializedPacket("Serialized UDP packet length mismatch", debugEnabled, debugSink);
        }

        byte[] ipBytes = new byte[ipLen];
        buffer.get(ipBytes);
        InetAddress address;
        try {
            address = InetAddress.getByAddress(ipBytes);
        } catch (Exception e) {
            emitMalformedPacketDebug(null, e, debugEnabled, debugSink);
            return null;
        }
        int port = buffer.getShort() & 0xFFFF;
        byte[] data = new byte[dataLen];
        buffer.get(data);

        return new DatagramPacket(data, data.length, address, port);
    }

    private static DatagramPacket invalidSerializedPacket(
            String message,
            boolean debugEnabled,
            BiConsumer<String, Throwable> debugSink
    ) {
        emitMalformedPacketDebug(message, null, debugEnabled, debugSink);
        return null;
    }

    private void transferDataToNeoServer() {
        try {
            while (true) { // 用异常退出循环。
                DatagramPacket incomingPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                plainSocket.receive(incomingPacket);
                byte[] serializedData = serializeDatagramPacket(incomingPacket);
                debug("Forwarding UDP packet from local service to NeoProxyServer. source="
                        + incomingPacket.getAddress().getHostAddress() + ":" + incomingPacket.getPort()
                        + ", payloadBytes=" + incomingPacket.getLength()
                        + ", serializedBytes=" + serializedData.length);
                secureSocket.sendBytes(serializedData);
            }
        } catch (IOException e) {
            debug(e);
        }
    }

    private byte[] serializeDatagramPacket(DatagramPacket packet) {
        serializationBuffer.clear();
        serializationBuffer.order(ByteOrder.BIG_ENDIAN);

        byte[] data = packet.getData();
        int offset = packet.getOffset();
        int length = packet.getLength();
        InetAddress address = packet.getAddress();
        int port = packet.getPort();

        byte[] ipBytes = address.getAddress();
        int ipLength = ipBytes.length;

        // 先校验序列化缓冲区容量是否足够，避免静默截断大包。
        // 这里选择直接抛异常，让调用方明确知道包太大。
        int totalLen = 4 + 4 + 4 + ipLength + 2 + length;
        if (totalLen > serializationBuffer.capacity()) {
            throw new IllegalArgumentException("UDP packet too large for serialization buffer: " + totalLen);
        }

        serializationBuffer.putInt(MAGIC);
        serializationBuffer.putInt(length);
        serializationBuffer.putInt(ipLength);
        serializationBuffer.put(ipBytes);
        serializationBuffer.putShort((short) port);
        serializationBuffer.put(data, offset, length);

        // 返回副本，避免后续复用 serializationBuffer 时覆盖正在发送的数据。
        return Arrays.copyOf(serializationBuffer.array(), serializationBuffer.position());
    }

    private void transferDataToLocalServer() {
        try {
            byte[] data;
            while ((data = secureSocket.receiveBytes()) != null) {
                debug("Received UDP frame from NeoProxyServer. serializedBytes=" + data.length);
                DatagramPacket datagramPacket = deserializeToDatagramPacket(data, debugEnabled, this::emitDebug);
                if (datagramPacket != null) {
                    DatagramPacket outgoingPacket = new DatagramPacket(
                            datagramPacket.getData(),
                            datagramPacket.getLength(),
                            localAddress,
                            localPort
                    );
                    plainSocket.send(outgoingPacket);
                    debug("Forwarded UDP packet to local service. target=" + localHost + ":" + localPort
                            + ", payloadBytes=" + datagramPacket.getLength());
                }
            }
        } catch (Exception e) {
            debug(e);
        }
    }

    @Override
    public void run() {
        try {
            debug("UDP transformer started. mode=" + (mode == MODE_NEO_TO_LOCAL ? "NEO_TO_LOCAL" : "LOCAL_TO_NEO")
                    + ", localTarget=" + localHost + ":" + localPort);
            if (mode == MODE_NEO_TO_LOCAL) {
                transferDataToLocalServer();
            } else {
                transferDataToNeoServer();
            }
        } catch (Exception e) {
            debug(e);
        } finally {
            // 无论正常结束还是异常结束，都确保走到统一的资源收尾路径。
            debug("UDP transformer finished.");
        }
    }

    private void debug(String message) {
        if (!debugEnabled) {
            return;
        }
        emitDebug(message, null);
    }

    private void debug(Exception e) {
        if (!debugEnabled || e == null) {
            return;
        }
        emitDebug(null, e);
    }

    private void emitDebug(String message, Throwable cause) {
        try {
            debugSink.accept(message, cause);
        } catch (RuntimeException ignored) {
            // Debug 回调只用于观测，不能干扰转发流程。
        }
    }

    private static void emitMalformedPacketDebug(
            String message,
            Throwable cause,
            boolean debugEnabled,
            BiConsumer<String, Throwable> debugSink
    ) {
        if (!debugEnabled) {
            return;
        }
        try {
            debugSink.accept(message, cause);
        } catch (RuntimeException ignored) {
            // 畸形包诊断只用于观测，不能干扰转发流程。
        }
    }

    private static InetAddress resolveLocalAddress(String localHost) {
        try {
            return InetAddress.getByName(localHost);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to resolve local UDP target host: " + localHost, e);
        }
    }

    private static void defaultDebugSink(String message, Throwable cause) {
        if (message != null) {
            Debugger.debugOperation(true, message);
        }
        if (cause instanceof Exception exception) {
            Debugger.debugOperation(true, exception);
        }
    }
}
