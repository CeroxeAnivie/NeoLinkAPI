package top.ceroxe.api.neolink.network;

import fun.ceroxe.api.net.SecureSocket;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Socket;

/**
 * Per-client proxy resolver.
 *
 * <p>The original client stored parsed proxy state in static fields because it
 * was a process-wide application. The API version keeps proxy state inside each
 * client instance so two embedding libraries can run independent tunnels in the
 * same JVM.</p>
 */
public final class ProxyOperator {
    public static final int TO_NEO = 0;
    public static final int TO_LOCAL = 1;

    private final ProxySettings toNeo;
    private final ProxySettings toLocal;

    public ProxyOperator(String remoteHost, String localHost, String proxyToNeoServer, String proxyToLocalServer) {
        this.toNeo = parse(proxyToNeoServer, remoteHost);
        this.toLocal = parse(proxyToLocalServer, localHost);
    }

    public Socket getHandledSocket(int socketType, int targetPort, int connectTimeoutMillis) throws IOException {
        ProxySettings settings = settingsFor(socketType);
        return withProxyAuthenticator(settings, () -> {
            Socket socket = new Socket(settings.proxy());
            socket.connect(new InetSocketAddress(settings.targetHost(), targetPort), connectTimeoutMillis);
            return socket;
        });
    }

    public SecureSocket getHandledSecureSocket(int socketType, int targetPort, int connectTimeoutMillis)
            throws IOException {
        ProxySettings settings = settingsFor(socketType);
        return withProxyAuthenticator(
                settings,
                () -> new SecureSocket(settings.proxy(), settings.targetHost(), targetPort, connectTimeoutMillis)
        );
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

    private static <T> T withProxyAuthenticator(ProxySettings settings, IOExceptionSupplier<T> supplier)
            throws IOException {
        if (!settings.hasCredentials()) {
            return supplier.get();
        }

        Authenticator previous = Authenticator.getDefault();
        try {
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(settings.username(), settings.password().toCharArray());
                }
            });
            return supplier.get();
        } finally {
            Authenticator.setDefault(previous);
        }
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

        Proxy proxy() {
            if (!hasProxy()) {
                return Proxy.NO_PROXY;
            }
            return new Proxy(proxyType, new InetSocketAddress(proxyHost, proxyPort));
        }

        boolean hasProxy() {
            return proxyType != null && proxyType != Proxy.Type.DIRECT;
        }

        boolean hasCredentials() {
            return hasProxy() && username != null && password != null;
        }
    }

    @FunctionalInterface
    private interface IOExceptionSupplier<T> {
        T get() throws IOException;
    }
}
