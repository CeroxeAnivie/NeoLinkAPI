package top.ceroxe.api.neolink;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class NeoLinkExecutors {
    private static final int DEFAULT_MAX_PLATFORM_WORKERS = 256;
    private static final String MAX_PLATFORM_WORKERS_PROPERTY = "neolink.desktop.maxWorkerThreads";

    private NeoLinkExecutors() {
    }

    static ExecutorService createDesktopWorkerExecutor() {
        ExecutorService virtualThreadExecutor = tryCreateVirtualThreadExecutor();
        if (virtualThreadExecutor != null) {
            return virtualThreadExecutor;
        }

        return NeoLinkWorkerExecutors.createBoundedDaemonExecutor(
                "neolink-desktop-worker-",
                maxPlatformWorkers()
        );
    }

    private static int maxPlatformWorkers() {
        return Integer.getInteger(MAX_PLATFORM_WORKERS_PROPERTY, DEFAULT_MAX_PLATFORM_WORKERS);
    }

    private static ExecutorService tryCreateVirtualThreadExecutor() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            Object executor = method.invoke(null);
            if (executor instanceof ExecutorService executorService) {
                return executorService;
            }
        } catch (NoSuchMethodException ignored) {
            // Java 17 is the published baseline; virtual threads are a Java 21+ runtime enhancement.
        } catch (IllegalAccessException | InvocationTargetException | SecurityException ignored) {
            // A restricted runtime should fall back to ordinary platform threads instead of failing startup.
        }
        return null;
    }
}
