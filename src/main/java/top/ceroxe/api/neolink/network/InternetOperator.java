package top.ceroxe.api.neolink.network;

import top.ceroxe.api.net.SecureSocket;
import top.ceroxe.api.neolink.util.Debugger;

import java.io.Closeable;
import java.net.Socket;

public final class InternetOperator {
    private InternetOperator() {
    }

    public static void close(Closeable... closeables) {
        for (Closeable closeable : closeables) {
            try {
                if (closeable != null) {
                    closeable.close();
                }
            } catch (Exception e) {
                Debugger.debugOperation(e);
            }
        }
    }

    public static void shutdownInput(SecureSocket socket) {
        try {
            if (socket != null) {
                socket.shutdownInput();
            }
        } catch (Exception ignored) {
        }
    }

    public static void shutdownInput(Socket socket) {
        try {
            if (socket != null) {
                socket.shutdownInput();
            }
        } catch (Exception ignored) {
        }
    }

    public static void shutdownOutput(SecureSocket socket) {
        try {
            if (socket != null) {
                socket.shutdownOutput();
            }
        } catch (Exception ignored) {
        }
    }

    public static void shutdownOutput(Socket socket) {
        try {
            if (socket != null) {
                socket.shutdownOutput();
            }
        } catch (Exception ignored) {
        }
    }
}
