/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ProgressivePromise;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * A delegating {@link EventExecutor} that makes sure all submitted tasks are
 * executed within the {@link RequestContext}.
 */
final class RequestContextAwareEventLoop implements EventLoop {

    private final RequestContext context;
    private final EventLoop delegate;

    RequestContextAwareEventLoop(RequestContext context,EventLoop delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    @Override
    public EventLoop next() {
        return delegate.next();
    }

    @Override
    public EventLoopGroup parent() {
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
        return new RequestContextAwarePromise<>(context, delegate.newPromise());
    }

    @Override
    public <V> ProgressivePromise<V> newProgressivePromise() {
        return new RequestContextAwareProgressivePromise<>(context, delegate.newProgressivePromise());
    }

    @Override
    public <V> Future<V> newSucceededFuture(V result) {
        return new RequestContextAwareFuture<>(context, delegate.newSucceededFuture(result));
    }

    @Override
    public <V> Future<V> newFailedFuture(Throwable cause) {
        return new RequestContextAwareFuture<>(context, delegate.newFailedFuture(cause));
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
    public Future<?> shutdownGracefully(long quietPeriod, long timeout,
                                        TimeUnit unit) {
        return delegate.shutdownGracefully(quietPeriod, timeout, unit);
    }

    @Override
    public Future<?> terminationFuture() {
        return delegate.terminationFuture();
    }

    @Override
    @Deprecated
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    @Deprecated
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    @Deprecated
    public Iterator<EventExecutor> iterator() {
        return delegate.iterator();
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(context.makeContextAware(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(context.makeContextAware(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(context.makeContextAware(task));
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay,
                                       TimeUnit unit) {
        return delegate.schedule(context.makeContextAware(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                           long delay, TimeUnit unit) {
        return delegate.schedule(context.makeContextAware(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                  long period,
                                                  TimeUnit unit) {
        return delegate.scheduleAtFixedRate(context.makeContextAware(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                     long delay,
                                                     TimeUnit unit) {
        return delegate.scheduleWithFixedDelay(context.makeContextAware(command), initialDelay, delay, unit);
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
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(makeContextAware(tasks));
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout,
            TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(makeContextAware(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(makeContextAware(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout,
                           TimeUnit unit) throws InterruptedException, ExecutionException,
                                                 TimeoutException {
        return delegate.invokeAny(makeContextAware(tasks), timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(context.makeContextAware(command));
    }

    private <T> Collection<? extends Callable<T>> makeContextAware(
            Collection<? extends Callable<T>> tasks) {
        return tasks.stream().map(context::makeContextAware).collect(Collectors.toList());
    }

    @Override
    public ChannelFuture register(Channel channel) {
        return delegate.register(channel);
    }

    @Override
    public ChannelFuture register(ChannelPromise channelPromise) {
        return delegate.register(channelPromise);
    }

    @Override
    @Deprecated
    public ChannelFuture register(Channel channel,
                                  ChannelPromise channelPromise) {
        return delegate.register(channel, channelPromise);
    }
}
