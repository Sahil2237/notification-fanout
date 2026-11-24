package com.example.notifications;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedExecutors {
    private BoundedExecutors() {}
    public static ExecutorService loops(String name,int threads) {
        var sequence=new AtomicInteger();
        return new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(true),
                task->new Thread(task,name+"-"+sequence.incrementAndGet()),new ThreadPoolExecutor.AbortPolicy());
    }
    /** Only one fixed recurring task is submitted by each owner; no per-event tasks enter this queue. */
    public static ScheduledThreadPoolExecutor poller(String name) {
        var executor=new ScheduledThreadPoolExecutor(1,task->new Thread(task,name),new ThreadPoolExecutor.AbortPolicy());
        executor.setMaximumPoolSize(1);executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);return executor;
    }
}
