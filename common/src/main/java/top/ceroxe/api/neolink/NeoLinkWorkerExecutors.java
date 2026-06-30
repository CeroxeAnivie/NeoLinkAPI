package top.ceroxe.api.neolink;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class NeoLinkWorkerExecutors {
    private NeoLinkWorkerExecutors() {
    }

    static ExecutorService createBoundedDaemonExecutor(String threadNamePrefix, int maxWorkers) {
        if (maxWorkers < 1) {
            throw new IllegalArgumentException("maxWorkers must be greater than 0.");
        }

        AtomicInteger workerId = new AtomicInteger(1);
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(
                    task,
                    Objects.requireNonNull(threadNamePrefix, "threadNamePrefix") + workerId.getAndIncrement()
            );
            thread.setDaemon(true);
            return thread;
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0,
                maxWorkers,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
