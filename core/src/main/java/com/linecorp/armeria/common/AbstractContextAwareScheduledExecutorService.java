package com.linecorp.armeria.common;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

abstract class AbstractContextAwareScheduledExecutorService extends AbstractContextAwareExecutorService
        implements ScheduledExecutorService {
    final ScheduledExecutorService executor;

    AbstractContextAwareScheduledExecutorService(ScheduledExecutorService executor) {
        super(executor);
        this.executor = executor;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return executor.schedule(makeContextAware(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return executor.schedule(makeContextAware(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                  TimeUnit unit) {
        return executor.scheduleAtFixedRate(makeContextAware(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        return executor.scheduleWithFixedDelay(makeContextAware(command), initialDelay, delay, unit);
    }
}
