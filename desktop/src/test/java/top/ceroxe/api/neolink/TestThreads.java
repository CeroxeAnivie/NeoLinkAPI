package top.ceroxe.api.neolink;

import java.util.concurrent.atomic.AtomicInteger;

public final class TestThreads {
    private static final AtomicInteger THREAD_ID = new AtomicInteger(1);

    private TestThreads() {
    }

    public static Thread start(Runnable task) {
        return start("neolink-test-worker-" + THREAD_ID.getAndIncrement(), task);
    }

    public static Thread start(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
