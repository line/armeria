package com.linecorp.armeria.common;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

abstract class AbstractContextAwareExecutorService implements ExecutorService {
    protected final ExecutorService executor;

    AbstractContextAwareExecutorService(ExecutorService executor) {this.executor = executor;}

    @Nullable
    protected abstract RequestContext context();

    @Override
    public final void shutdown() {
        executor.shutdown();
    }

    @Override
    public final List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    @Override
    public Future<?> submit(Runnable task) {
        return executor.submit(makeContextAware(task));
    }

    protected final Runnable makeContextAware(Runnable task) {
        final RequestContext context = context();
        return null == context ? task : context.makeContextAware(task);
    }

    protected final <T> Callable<T> makeContextAware(Callable<T> task) {
        final RequestContext context = context();
        return null == context ? task : context.makeContextAware(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return executor.submit(makeContextAware(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(makeContextAware(task));
    }

    @Override
    public final boolean isShutdown() {
        return executor.isShutdown();
    }

    @Override
    public final boolean isTerminated() {
        return executor.isTerminated();
    }

    @Override
    public final boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    @Override
    public final <T> List<Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return executor.invokeAll(makeContextAware(tasks));
    }

    @Override
    public final <T> List<Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return executor.invokeAll(makeContextAware(tasks), timeout, unit);
    }

    @Override
    public final <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return executor.invokeAny(makeContextAware(tasks));
    }

    @Override
    public final <T> T invokeAny(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return executor.invokeAny(makeContextAware(tasks), timeout, unit);
    }

    @Override
    public final void execute(Runnable command) {
        executor.execute(makeContextAware(command));
    }

    private <T> Collection<? extends Callable<T>> makeContextAware(
            Collection<? extends Callable<T>> tasks) {
        return tasks.stream().map(this::makeContextAware).collect(Collectors.toList());
    }
}
