package top.ceroxe.api.neolink.transparency;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * API 透明性校验用客户端。
 *
 * <p>调用方式：
 * <pre>{@code
 * java ... top.ceroxe.api.neolink.transparency.APITransparencyClient host:port
 * java ... top.ceroxe.api.neolink.transparency.APITransparencyClient [ipv6-host]:port
 * }</pre>
 *
 * <p>该类故意不依赖额外协议，只用最朴素的 TCP/UDP 回显校验透明性：
 * 只要回来的字节序列与发出去的完全一致，就说明链路没有偷偷改写载荷。
 */
public final class APITransparencyClient {
    private static final int TCP_CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int TCP_SO_TIMEOUT_MILLIS = 30_000;
    private static final int UDP_SO_TIMEOUT_MILLIS = 8_000;
    private static final int UDP_MAX_PAYLOAD = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private APITransparencyClient() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("用法: APITransparencyClient <tunAddr>");
            System.err.println("示例: APITransparencyClient p.ceroxe.top:41234");
            System.exit(2);
            return;
        }

        int exitCode = runChecks(args[0]);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runChecks(String tunAddr) throws Exception {
        if (tunAddr == null || tunAddr.isBlank()) {
            throw new IllegalArgumentException("tunAddr must not be blank");
        }

        TargetAddress target = TargetAddress.parse(tunAddr);
        Report report = new Report(target.raw());

        Instant begin = Instant.now();
        execute(report, "tcp-connect-close", APITransparencyClient::testTcpConnectClose, target);
        execute(report, "tcp-small-text", APITransparencyClient::testTcpSmallText, target);
        execute(report, "tcp-binary-boundaries", APITransparencyClient::testTcpBinaryBoundaries, target);
        execute(report, "tcp-half-close", APITransparencyClient::testTcpHalfClose, target);
        execute(report, "tcp-concurrency", APITransparencyClient::testTcpConcurrency, target);
        executeWarning(report, "udp-empty-payload", APITransparencyClient::testUdpEmptyPayload, target);
        execute(report, "udp-binary-boundaries", APITransparencyClient::testUdpBinaryBoundaries, target);
        execute(report, "udp-burst", APITransparencyClient::testUdpBurst, target);
        report.finish(Duration.between(begin, Instant.now()));

        System.out.println(report.render());
        return report.failed() > 0 ? 1 : 0;
    }

    private static void execute(Report report, String name, CaseExecutor executor, TargetAddress target) {
        Instant begin = Instant.now();
        try {
            String detail = executor.run(target);
            report.add(name, true, Duration.between(begin, Instant.now()), detail, null);
        } catch (Exception e) {
            report.add(name, false, Duration.between(begin, Instant.now()), null, e);
        }
    }

    private static void executeWarning(Report report, String name, CaseExecutor executor, TargetAddress target) {
        Instant begin = Instant.now();
        try {
            String detail = executor.run(target);
            report.add(name, true, Duration.between(begin, Instant.now()), detail, null);
        } catch (Exception e) {
            report.addWarning(name, Duration.between(begin, Instant.now()), e);
        }
    }

    private static String testTcpConnectClose(TargetAddress target) throws IOException {
        try (Socket socket = createTcpSocket(target)) {
            socket.shutdownOutput();
            drainUntilEof(socket.getInputStream());
        }
        return "connect/close 成功";
    }

    private static String testTcpSmallText(TargetAddress target) throws IOException {
        byte[] payload = "API transparency probe: hello, world.\n第二行 mixed UTF-8.\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] echoed = roundTripTcp(target, payload, false);
        assertBytesEqual("tcp-small-text", payload, echoed);
        return "bytes=" + payload.length;
    }

    private static String testTcpBinaryBoundaries(TargetAddress target) throws IOException {
        int[] lengths = {1, 7, 64, 1024, 8_192, 65_536, 262_144};
        for (int length : lengths) {
            byte[] payload = binaryPattern(length);
            byte[] echoed = roundTripTcp(target, payload, false);
            assertBytesEqual("tcp-binary-" + length, payload, echoed);
        }
        return "cases=" + lengths.length + ", maxBytes=262144";
    }

    private static String testTcpHalfClose(TargetAddress target) throws IOException {
        byte[] payload = randomBytes(131_072);
        byte[] echoed = roundTripTcp(target, payload, true);
        assertBytesEqual("tcp-half-close", payload, echoed);
        return "bytes=" + payload.length;
    }

    private static String testTcpConcurrency(TargetAddress target) throws InterruptedException, ExecutionException {
        int sessions = 12;
        int bytesPerSession = 32_768;
        try (var executor = Executors.newFixedThreadPool(sessions)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (int index = 0; index < sessions; index++) {
                final int sessionId = index;
                futures.add(executor.submit(new Callable<>() {
                    @Override
                    public Void call() throws Exception {
                        byte[] payload = randomBytes(bytesPerSession);
                        payload[0] = (byte) sessionId;
                        byte[] echoed = roundTripTcp(target, payload, true);
                        assertBytesEqual("tcp-concurrency-" + sessionId, payload, echoed);
                        return null;
                    }
                }));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        }
        return "sessions=" + sessions + ", bytesPerSession=" + bytesPerSession;
    }

    private static String testUdpEmptyPayload(TargetAddress target) throws IOException {
        try (DatagramSocket socket = createUdpSocket()) {
            warmUpUdp(socket, target);
            byte[] payload = new byte[0];
            byte[] echoed = roundTripUdp(socket, target, payload, 2);
            assertBytesEqual("udp-empty-payload", payload, echoed);
        }
        return "bytes=0";
    }

    private static String testUdpBinaryBoundaries(TargetAddress target) throws IOException {
        int[] lengths = {1, 7, 64, 512, 1_472, 4_096, 16_384, 32_768, UDP_MAX_PAYLOAD};
        try (DatagramSocket socket = createUdpSocket()) {
            for (int length : lengths) {
                byte[] payload = randomBytes(length);
                byte[] echoed = roundTripUdp(socket, target, payload, 3);
                assertBytesEqual("udp-binary-" + length, payload, echoed);
            }
        }
        return "cases=" + lengths.length + ", maxBytes=" + UDP_MAX_PAYLOAD;
    }

    private static String testUdpBurst(TargetAddress target) throws IOException {
        int packets = 64;
        int payloadBytes = 1_024;
        try (DatagramSocket socket = createUdpSocket()) {
            InetSocketAddress remote = target.toInetSocketAddress();
            byte[] receiveBuffer = new byte[UDP_MAX_PAYLOAD + 16];
            for (int index = 0; index < packets; index++) {
                byte[] payload = randomBytes(payloadBytes);
                payload[0] = (byte) index;
                socket.send(new DatagramPacket(payload, payload.length, remote));

                DatagramPacket inbound = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(inbound);
                byte[] echoed = Arrays.copyOf(inbound.getData(), inbound.getLength());
                assertBytesEqual("udp-burst-" + index, payload, echoed);
            }
        }
        return "packets=" + packets + ", bytesPerPacket=" + payloadBytes;
    }

    private static Socket createTcpSocket(TargetAddress target) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(TCP_SO_TIMEOUT_MILLIS);
        socket.connect(target.toInetSocketAddress(), TCP_CONNECT_TIMEOUT_MILLIS);
        return socket;
    }

    private static byte[] roundTripTcp(TargetAddress target, byte[] payload, boolean shutdownOutput) throws IOException {
        try (Socket socket = createTcpSocket(target);
             OutputStream output = socket.getOutputStream();
             InputStream input = socket.getInputStream()) {
            Future<byte[]> readerFuture;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                readerFuture = executor.submit(() -> readExactly(input, payload.length));
                writeChunked(output, payload);
                if (shutdownOutput) {
                    socket.shutdownOutput();
                }

                byte[] echoed;
                try {
                    echoed = readerFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("TCP reader 被中断", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IOException("TCP reader 失败", cause);
                }

                if (shutdownOutput) {
                    drainUntilEof(input);
                }
                return echoed;
            }
        }
    }

    private static byte[] roundTripUdp(DatagramSocket socket, TargetAddress target, byte[] payload, int attempts) throws IOException {
        byte[] receiveBuffer = new byte[UDP_MAX_PAYLOAD + 16];
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                socket.send(new DatagramPacket(payload, payload.length, target.toInetSocketAddress()));
                DatagramPacket inbound = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(inbound);
                return Arrays.copyOf(inbound.getData(), inbound.getLength());
            } catch (SocketTimeoutException e) {
                lastFailure = new IOException("UDP response timed out at attempt " + attempt, e);
            }
        }
        throw lastFailure == null ? new IOException("UDP response 失败，且没有显式原因") : lastFailure;
    }

    private static void writeChunked(OutputStream output, byte[] payload) throws IOException {
        int offset = 0;
        int toggle = 257;
        while (offset < payload.length) {
            int chunk = Math.min(toggle, payload.length - offset);
            output.write(payload, offset, chunk);
            output.flush();
            offset += chunk;
            toggle = toggle == 257 ? 4093 : 257;
        }
    }

    private static byte[] readExactly(InputStream input, int expectedBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(expectedBytes);
        byte[] chunk = new byte[16 * 1024];
        while (buffer.size() < expectedBytes) {
            int read = input.read(chunk, 0, Math.min(chunk.length, expectedBytes - buffer.size()));
            if (read < 0) {
                throw new EOFException("意外 EOF, expected=" + expectedBytes + ", actual=" + buffer.size());
            }
            if (read == 0) {
                continue;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void drainUntilEof(InputStream input) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        while (input.read(buffer) >= 0) {
            // 故意读到 EOF，目的是验证链路半关闭时不会卡死在对端。
        }
    }

    private static void assertBytesEqual(String caseName, byte[] expected, byte[] actual) throws IOException {
        if (!Arrays.equals(expected, actual)) {
            throw new IOException(caseName + " payload 不一致, expectedBytes=" + expected.length + ", actualBytes=" + actual.length);
        }
    }

    private static byte[] binaryPattern(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index & 0xFF);
        }
        return bytes;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static DatagramSocket createUdpSocket() throws IOException {
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(UDP_SO_TIMEOUT_MILLIS);
        return socket;
    }

    private static void warmUpUdp(DatagramSocket socket, TargetAddress target) throws IOException {
        byte[] payload = new byte[]{0x5A};
        byte[] echoed = roundTripUdp(socket, target, payload, 3);
        assertBytesEqual("udp-warm-up", payload, echoed);
    }

    @FunctionalInterface
    private interface CaseExecutor {
        String run(TargetAddress target) throws Exception;
    }

    private record TargetAddress(String raw, String host, int port) {
        private TargetAddress {
            Objects.requireNonNull(raw, "raw");
            Objects.requireNonNull(host, "host");
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port 必须在 1..65535 之间");
            }
        }

        private static TargetAddress parse(String raw) {
            String value = raw.trim();
            if (value.startsWith("[")) {
                int closing = value.indexOf(']');
                if (closing < 0 || closing + 2 > value.length() || value.charAt(closing + 1) != ':') {
                    throw new IllegalArgumentException("IPv6 tunAddr 非法: " + raw);
                }
                String host = value.substring(1, closing);
                int port = Integer.parseInt(value.substring(closing + 2));
                return new TargetAddress(raw, host, port);
            }

            int lastColon = value.lastIndexOf(':');
            if (lastColon <= 0 || lastColon == value.length() - 1) {
                throw new IllegalArgumentException("tunAddr 非法，期望格式为 host:port");
            }
            String host = value.substring(0, lastColon);
            int port = Integer.parseInt(value.substring(lastColon + 1));
            return new TargetAddress(raw, host, port);
        }

        private InetSocketAddress toInetSocketAddress() {
            return new InetSocketAddress(host, port);
        }
    }

    private static final class Report {
        private final String target;
        private final List<String> lines = new ArrayList<>();
        private int passed;
        private int warned;
        private int failed;
        private Duration totalDuration = Duration.ZERO;

        private Report(String target) {
            this.target = target;
        }

        private void add(String caseName, boolean ok, Duration duration, String detail, Exception error) {
            if (ok) {
                passed++;
                lines.add("[PASS] " + caseName + " (" + duration.toMillis() + " ms) - " + detail);
                return;
            }

            failed++;
            String message = error == null ? "未知失败" : error.getClass().getSimpleName() + ": " + error.getMessage();
            lines.add("[FAIL] " + caseName + " (" + duration.toMillis() + " ms) - " + message);
        }

        private void addWarning(String caseName, Duration duration, Exception error) {
            warned++;
            String message = error == null ? "未知告警" : error.getClass().getSimpleName() + ": " + error.getMessage();
            lines.add("[WARN] " + caseName + " (" + duration.toMillis() + " ms) - " + message);
        }

        private void finish(Duration totalDuration) {
            this.totalDuration = totalDuration;
        }

        private int failed() {
            return failed;
        }

        private String render() {
            StringBuilder builder = new StringBuilder(1024);
            builder.append("=== API Transparency Report / API 透明性校验报告 ===\n");
            builder.append("target=").append(target).append('\n');
            builder.append("passed=").append(passed).append(", warned=").append(warned).append(", failed=").append(failed)
                    .append(", totalMillis=").append(totalDuration.toMillis()).append('\n');
            for (String line : lines) {
                builder.append(line).append('\n');
            }
            builder.append("overall=").append(failed == 0 ? "PASS" : "FAIL").append('\n');
            return builder.toString();
        }
    }
}
