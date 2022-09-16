/*
 * Copyright 2022 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.util;

import static com.google.common.base.Preconditions.checkArgument;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.throttling.ThrottlingService;
import com.linecorp.armeria.server.throttling.ThrottlingStrategy;

final class DefaultLimitedBlockingTaskExecutor implements LimitedBlockingTaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(DefaultLimitedBlockingTaskExecutor.class);

    private final BlockingTaskExecutor delegate;

    private final SettableIntSupplier limitSupplier;

    private final Supplier<Integer> currentQueueingSize;

    DefaultLimitedBlockingTaskExecutor(BlockingTaskExecutor delegate, SettableIntSupplier limitSupplier) {
        this.delegate = delegate;
        this.limitSupplier = limitSupplier;
        final ThreadPoolExecutor executor = unwrapThreadPoolExecutor();
        currentQueueingSize = () -> executor.getQueue().size();
    }

    @Override
    public Function<? super HttpService, ThrottlingService> asDecorator(@Nullable String name) {
        return ThrottlingService.newDecorator(ThrottlingStrategy.blockingTaskLimiting(this, name));
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return delegate.schedule(command, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return delegate.schedule(callable, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                  TimeUnit unit) {
        return delegate.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        return delegate.scheduleAtFixedRate(command, initialDelay, delay, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return delegate.isShutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    @Override
    public ScheduledExecutorService unwrap() {
        return delegate;
    }

    @Override
    public boolean hitLimit() {
        checkArgument(limitSupplier.getAsInt() > 0, "limit must larger than zero");
        return currentQueueingSize.get() >= limitSupplier.getAsInt();
    }

    private ThreadPoolExecutor unwrapThreadPoolExecutor() {
        try {
            final Field executor = getClass().getSuperclass().getSuperclass().getSuperclass()
                                             .getDeclaredField("executor");
            executor.setAccessible(true);
            final LimitedBlockingTaskExecutor limitedBlockingTaskExecutor =
                    (LimitedBlockingTaskExecutor) executor.get(this);
            final Field delegate = limitedBlockingTaskExecutor.getClass().getDeclaredField("delegate");
            delegate.setAccessible(true);
            return (ThreadPoolExecutor) delegate.get(limitedBlockingTaskExecutor);
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
            logger.info("Cannot unwrap ThreadPoolExecutor", e);
            throw new IllegalStateException("Cannot throttle unwrap ThreadPoolExecutor", e);
        }
    }
}
