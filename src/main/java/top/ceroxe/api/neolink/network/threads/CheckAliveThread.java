package top.ceroxe.api.neolink.network.threads;

import fun.ceroxe.api.net.SecureSocket;
import top.ceroxe.api.neolink.network.InternetOperator;
import top.ceroxe.api.neolink.util.Debugger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * NeoLink 心跳检测线程。
 *
 * <p>每个 API 实例独立持有一个心跳检测线程，避免多个隧道在同一个 JVM 内共享连接状态。
 * 线程只在控制连接长时间空闲时发送心跳，并在连续失败后关闭控制连接，让主循环统一退出。</p>
 */
public final class CheckAliveThread implements Runnable {
    private static final String HEARTBEAT_PACKET = "PING";
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final int HEARTBEAT_IDLE_MILLIS = 2_000;

    private final Supplier<SecureSocket> hookSocketSupplier;
    private final LongSupplier lastReceivedTimeSupplier;
    private final int heartbeatPacketDelay;
    private final BiConsumer<String, Throwable> errorHandler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private Thread heartbeatThreadInstance;

    public CheckAliveThread(
            Supplier<SecureSocket> hookSocketSupplier,
            LongSupplier lastReceivedTimeSupplier,
            int heartbeatPacketDelay,
            BiConsumer<String, Throwable> errorHandler
    ) {
        this.hookSocketSupplier = Objects.requireNonNull(hookSocketSupplier, "hookSocketSupplier");
        this.lastReceivedTimeSupplier = Objects.requireNonNull(lastReceivedTimeSupplier, "lastReceivedTimeSupplier");
        this.heartbeatPacketDelay = requirePositive(heartbeatPacketDelay, "heartbeatPacketDelay");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    public Thread startThread() {
        if (isRunning.compareAndSet(false, true)) {
            heartbeatThreadInstance = new Thread(this, "Client-CheckAliveThread");
            heartbeatThreadInstance.setDaemon(true);
            heartbeatThreadInstance.start();
        }
        return heartbeatThreadInstance;
    }

    public void stopThread() {
        if (isRunning.compareAndSet(true, false)) {
            Thread thread = heartbeatThreadInstance;
            if (thread != null) {
                thread.interrupt();
                if (thread != Thread.currentThread()) {
                    try {
                        thread.join(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    @Override
    public void run() {
        AtomicInteger failureCount = new AtomicInteger(0);

        while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
            SecureSocket hookSocket = hookSocketSupplier.get();
            if (hookSocket == null || hookSocket.isClosed()) {
                isRunning.set(false);
                break;
            }

            long timeSinceLastReceived = System.currentTimeMillis() - lastReceivedTimeSupplier.getAsLong();
            if (timeSinceLastReceived > HEARTBEAT_IDLE_MILLIS) {
                try {
                    synchronized (hookSocket) {
                        hookSocket.sendStr(HEARTBEAT_PACKET);
                    }
                    failureCount.set(0);
                } catch (Exception e) {
                    int currentFailures = failureCount.incrementAndGet();
                    if (currentFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Debugger.debugOperation(e);
                        errorHandler.accept("NeoProxyServer heartbeat failed.", e);
                        InternetOperator.close(hookSocket);
                        isRunning.set(false);
                        break;
                    }
                }
            } else {
                failureCount.set(0);
            }

            try {
                Thread.sleep(heartbeatPacketDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static int requirePositive(int value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0.");
        }
        return value;
    }
}
