package top.ceroxe.api.neolink;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class NeoLinkExecutors {
    private static final AtomicInteger WORKER_THREAD_ID = new AtomicInteger(1);

    private NeoLinkExecutors() {
    }

    static ExecutorService createDesktopWorkerExecutor() {
        ExecutorService virtualThreadExecutor = tryCreateVirtualThreadExecutor();
        if (virtualThreadExecutor != null) {
            return virtualThreadExecutor;
        }

        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "neolink-desktop-worker-" + WORKER_THREAD_ID.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(threadFactory);
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
