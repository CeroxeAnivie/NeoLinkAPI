package top.ceroxe.api.neolink.network;

import top.ceroxe.api.net.SecureSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 每个客户端实例独立持有的代理解析器。
 *
 * <p>原始客户端因为是进程级应用，会把解析后的代理状态放在静态字段里。API 版本则
 * 把代理状态收敛到每个客户端实例内部。代理认证也在这个对象自己的 SOCKS5 / HTTP
 * CONNECT 握手过程中完成，而不是安装一个全局的 {@link java.net.Authenticator}。</p>
 */
public final class ProxyOperator {
    public static final int TO_NEO = 0;
    public static final int TO_LOCAL = 1;
    private static final int SOCKS_VERSION = 0x05;
    private static final int SOCKS_AUTH_VERSION = 0x01;
    private static final int SOCKS_NO_AUTH = 0x00;
    private static final int SOCKS_USER_PASS = 0x02;
    private static final int SOCKS_CONNECT = 0x01;
    private static final int SOCKS_ADDRESS_IPV4 = 0x01;
    private static final int SOCKS_ADDRESS_DOMAIN = 0x03;
    private static final int SOCKS_ADDRESS_IPV6 = 0x04;
    private static final int MAX_SOCKS_FIELD_LENGTH = 255;

    private final ProxySettings toNeo;
    private final ProxySettings toLocal;

    public ProxyOperator(String remoteHost, String localHost, String proxyToNeoServer, String proxyToLocalServer) {
        this.toNeo = parse(proxyToNeoServer, remoteHost);
        this.toLocal = parse(proxyToLocalServer, localHost);
    }

    public Socket getHandledSocket(int socketType, int targetPort, int connectTimeoutMillis) throws IOException {
        ProxySettings settings = settingsFor(socketType);
        Socket socket = new Socket();
        try {
            if (!settings.hasProxy()) {
                socket.connect(new InetSocketAddress(settings.targetHost(), targetPort), connectTimeoutMillis);
                return socket;
            }

            socket.connect(new InetSocketAddress(settings.proxyHost(), settings.proxyPort()), connectTimeoutMillis);
            socket.setSoTimeout(connectTimeoutMillis);
            if (settings.proxyType() == Proxy.Type.SOCKS) {
                connectViaSocks5(socket, settings, targetPort);
            } else if (settings.proxyType() == Proxy.Type.HTTP) {
                connectViaHttp(socket, settings, targetPort);
            } else {
                throw new IOException("Unsupported proxy type: " + settings.proxyType());
            }
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public SecureSocket getHandledSecureSocket(int socketType, int targetPort, int connectTimeoutMillis)
            throws IOException {
        return new SecureSocket(getHandledSocket(socketType, targetPort, connectTimeoutMillis));
    }

    public boolean hasProxy(int socketType) {
        return settingsFor(socketType).hasProxy();
    }

    private ProxySettings settingsFor(int socketType) {
        return socketType == TO_NEO ? toNeo : toLocal;
    }

    private static ProxySettings parse(String proxyConfig, String targetHost) {
        if (proxyConfig == null || proxyConfig.isBlank()) {
            return ProxySettings.direct(targetHost);
        }

        String[] typeAndProperty = proxyConfig.trim().split("->", 2);
        if (typeAndProperty.length != 2 || typeAndProperty[1].isBlank()) {
            throw new IllegalArgumentException("Invalid proxy format. Expected type->host:port[@user;password].");
        }

        Proxy.Type proxyType = switch (typeAndProperty[0].trim().toLowerCase()) {
            case "socks" -> Proxy.Type.SOCKS;
            case "http" -> Proxy.Type.HTTP;
            case "direct" -> Proxy.Type.DIRECT;
            default -> throw new IllegalArgumentException("Unsupported proxy type: " + typeAndProperty[0]);
        };

        String[] authParts = typeAndProperty[1].split("@", 2);
        HostPort hostPort = parseHostPort(authParts[0]);

        String username = null;
        String password = null;
        if (authParts.length > 1) {
            String[] userPass = authParts[1].split(";", 2);
            if (userPass.length != 2 || userPass[0].isBlank()) {
                throw new IllegalArgumentException("Invalid proxy authentication format. Expected user;password.");
            }
            username = userPass[0];
            password = userPass[1];
        }

        return new ProxySettings(proxyType, hostPort.host(), hostPort.port(), targetHost, username, password);
    }

    private static HostPort parseHostPort(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Proxy address must not be blank.");
        }

        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            int closingBracket = trimmed.indexOf(']');
            if (closingBracket <= 1 || closingBracket + 2 > trimmed.length() || trimmed.charAt(closingBracket + 1) != ':') {
                throw new IllegalArgumentException("Invalid IPv6 proxy address: " + value);
            }
            return new HostPort(trimmed.substring(1, closingBracket), parseProxyPort(trimmed.substring(closingBracket + 2)));
        }

        String[] parts = trimmed.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank()) {
            throw new IllegalArgumentException("Invalid proxy address: " + value);
        }
        return new HostPort(parts[0], parseProxyPort(parts[1]));
    }

    private static int parseProxyPort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Proxy port must be between 1 and 65535.");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Proxy port must be an integer.", e);
        }
    }

    private static void connectViaSocks5(Socket socket, ProxySettings settings, int targetPort) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        if (settings.hasCredentials()) {
            out.write(new byte[]{SOCKS_VERSION, 0x02, SOCKS_NO_AUTH, SOCKS_USER_PASS});
        } else {
            out.write(new byte[]{SOCKS_VERSION, 0x01, SOCKS_NO_AUTH});
        }
        out.flush();

        byte[] negotiation = readExact(in, 2);
        if ((negotiation[0] & 0xFF) != SOCKS_VERSION) {
            throw new IOException("Invalid SOCKS5 negotiation response.");
        }

        int selectedMethod = negotiation[1] & 0xFF;
        if (selectedMethod == 0xFF) {
            throw new IOException("SOCKS5 proxy rejected all authentication methods.");
        }
        if (selectedMethod == SOCKS_USER_PASS) {
            authenticateSocks5(in, out, settings);
        } else if (selectedMethod != SOCKS_NO_AUTH) {
            throw new IOException("Unsupported SOCKS5 authentication method: " + selectedMethod);
        }

        out.write(SOCKS_VERSION);
        out.write(SOCKS_CONNECT);
        out.write(0x00);
        writeSocksAddress(out, settings.targetHost());
        writePort(out, targetPort);
        out.flush();

        byte[] response = readExact(in, 4);
        if ((response[0] & 0xFF) != SOCKS_VERSION) {
            throw new IOException("Invalid SOCKS5 connect response.");
        }
        int status = response[1] & 0xFF;
        if (status != 0x00) {
            throw new IOException("SOCKS5 connect failed with status: " + status);
        }
        consumeSocksBoundAddress(in, response[3] & 0xFF);
    }

    private static void authenticateSocks5(InputStream in, OutputStream out, ProxySettings settings) throws IOException {
        byte[] username = settings.username().getBytes(StandardCharsets.UTF_8);
        byte[] password = settings.password().getBytes(StandardCharsets.UTF_8);
        if (username.length > MAX_SOCKS_FIELD_LENGTH || password.length > MAX_SOCKS_FIELD_LENGTH) {
            throw new IOException("SOCKS5 username and password must be at most 255 bytes.");
        }

        out.write(SOCKS_AUTH_VERSION);
        out.write(username.length);
        out.write(username);
        out.write(password.length);
        out.write(password);
        out.flush();

        byte[] response = readExact(in, 2);
        if ((response[0] & 0xFF) != SOCKS_AUTH_VERSION || response[1] != 0x00) {
            throw new IOException("SOCKS5 username/password authentication failed.");
        }
    }

    private static void writeSocksAddress(OutputStream out, String host) throws IOException {
        String unbracketedHost = unbracketIpv6(host);
        if (isIPv4Literal(unbracketedHost)) {
            out.write(SOCKS_ADDRESS_IPV4);
            out.write(java.net.InetAddress.getByName(unbracketedHost).getAddress());
            return;
        }
        if (isIPv6Literal(unbracketedHost)) {
            out.write(SOCKS_ADDRESS_IPV6);
            out.write(java.net.InetAddress.getByName(unbracketedHost).getAddress());
            return;
        }

        byte[] hostBytes = unbracketedHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > MAX_SOCKS_FIELD_LENGTH) {
            throw new IOException("SOCKS5 target host is too long: " + unbracketedHost);
        }
        out.write(SOCKS_ADDRESS_DOMAIN);
        out.write(hostBytes.length);
        out.write(hostBytes);
    }

    private static void consumeSocksBoundAddress(InputStream in, int addressType) throws IOException {
        switch (addressType) {
            case SOCKS_ADDRESS_IPV4 -> readExact(in, 4);
            case SOCKS_ADDRESS_IPV6 -> readExact(in, 16);
            case SOCKS_ADDRESS_DOMAIN -> {
                int length = readByte(in);
                readExact(in, length);
            }
            default -> throw new IOException("Unsupported SOCKS5 bound address type: " + addressType);
        }
        readExact(in, 2);
    }

    private static void connectViaHttp(Socket socket, ProxySettings settings, int targetPort) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        String authority = formatAuthority(settings.targetHost(), targetPort);
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n")
                .append("Proxy-Connection: Keep-Alive\r\n");
        if (settings.hasCredentials()) {
            String token = settings.username() + ":" + settings.password();
            request.append("Proxy-Authorization: Basic ")
                    .append(Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)))
                    .append("\r\n");
        }
        request.append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String responseHeader = readHttpHeader(in);
        String statusLine = responseHeader.lines().findFirst().orElse("");
        if (!statusLine.startsWith("HTTP/")) {
            throw new IOException("Invalid HTTP proxy response: " + statusLine);
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !"200".equals(parts[1])) {
            throw new IOException("HTTP proxy CONNECT failed: " + statusLine);
        }
    }

    private static String readHttpHeader(InputStream in) throws IOException {
        StringBuilder header = new StringBuilder();
        int state = 0;
        while (true) {
            int next = readByte(in);
            header.append((char) next);
            state = switch (state) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : 0;
                default -> state;
            };
            if (state == 4) {
                return header.toString();
            }
            if (header.length() > 16 * 1024) {
                throw new IOException("HTTP proxy response header is too large.");
            }
        }
    }

    private static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IOException("Proxy closed the connection during handshake.");
            }
            offset += read;
        }
        return buffer;
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("Proxy closed the connection during handshake.");
        }
        return value;
    }

    private static void writePort(OutputStream out, int port) throws IOException {
        out.write((port >>> 8) & 0xFF);
        out.write(port & 0xFF);
    }

    private static String formatAuthority(String host, int port) {
        String unbracketedHost = unbracketIpv6(host);
        if (isIPv6Literal(unbracketedHost)) {
            return "[" + unbracketedHost + "]:" + port;
        }
        return unbracketedHost + ":" + port;
    }

    private static String unbracketIpv6(String host) {
        String trimmed = host.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isIPv4Literal(String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static boolean isIPv6Literal(String host) {
        return host.contains(":");
    }

    private record HostPort(String host, int port) {
    }

    private record ProxySettings(
            Proxy.Type proxyType,
            String proxyHost,
            int proxyPort,
            String targetHost,
            String username,
            String password
    ) {
        static ProxySettings direct(String targetHost) {
            return new ProxySettings(Proxy.Type.DIRECT, "", 0, targetHost, null, null);
        }

        boolean hasProxy() {
            return proxyType != null && proxyType != Proxy.Type.DIRECT;
        }

        boolean hasCredentials() {
            return hasProxy() && username != null && password != null;
        }
    }
}
