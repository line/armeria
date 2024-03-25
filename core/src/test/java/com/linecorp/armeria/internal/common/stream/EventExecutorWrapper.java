/*
 * Copyright 2023 LINE Corporation
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
 *
 */

package com.linecorp.armeria.internal.common.stream;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ProgressivePromise;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;

class EventExecutorWrapper implements EventExecutor {
    private final EventExecutor delegate;

    EventExecutorWrapper(EventExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean isShuttingDown() {
        return delegate.isShuttingDown();
    }

    @Override
    public Future<?> shutdownGracefully() {
        return delegate.shutdownGracefully();
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        return delegate.shutdownGracefully(quietPeriod, timeout, unit);
    }

    @Override
    public Future<?> terminationFuture() {
        return delegate.terminationFuture();
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
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public EventExecutor next() {
        return delegate.next();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return delegate.iterator();
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                                              long timeout, TimeUnit unit)
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
    public Future<?> submit(Runnable task) {
        return delegate.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(task, result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(task);
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
        return delegate.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    @Override
    public EventExecutorGroup parent() {
        return delegate.parent();
    }

    @Override
    public boolean inEventLoop() {
        return delegate.inEventLoop();
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return delegate.inEventLoop(thread);
    }

    @Override
    public <V> Promise<V> newPromise() {
        return delegate.newPromise();
    }

    @Override
    public <V> ProgressivePromise<V> newProgressivePromise() {
        return delegate.newProgressivePromise();
    }

    @Override
    public <V> Future<V> newSucceededFuture(V result) {
        return delegate.newSucceededFuture(result);
    }

    @Override
    public <V> Future<V> newFailedFuture(Throwable cause) {
        return delegate.newFailedFuture(cause);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }
}
