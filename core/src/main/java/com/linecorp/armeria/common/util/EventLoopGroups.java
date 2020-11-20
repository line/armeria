/*
 * Copyright 2017 LINE Corporation
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
import static java.util.Objects.requireNonNull;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.Flags;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.AbstractEventLoop;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.Future;

/**
 * Provides methods that are useful for creating an {@link EventLoopGroup}.
 *
 * @see ThreadFactories#newEventLoopThreadFactory(String, boolean)
 */
public final class EventLoopGroups {

    private static final EventLoop directEventLoop = new DirectEventLoop();

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads) {
        return newEventLoopGroup(numThreads, false);
    }

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param useDaemonThreads whether to create daemon threads or not
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads, boolean useDaemonThreads) {
        return newEventLoopGroup(numThreads, "armeria-eventloop", useDaemonThreads);
    }

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadNamePrefix the prefix of thread names
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads, String threadNamePrefix) {
        return newEventLoopGroup(numThreads, threadNamePrefix, false);
    }

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadNamePrefix the prefix of thread names
     * @param useDaemonThreads whether to create daemon threads or not
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads, String threadNamePrefix,
                                                   boolean useDaemonThreads) {

        checkArgument(numThreads > 0, "numThreads: %s (expected: > 0)", numThreads);
        requireNonNull(threadNamePrefix, "threadNamePrefix");

        final TransportType type = Flags.transportType();
        final String prefix = threadNamePrefix + '-' + type.lowerCasedName();
        return newEventLoopGroup(numThreads, ThreadFactories.newEventLoopThreadFactory(prefix,
                                                                                       useDaemonThreads));
    }

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadFactory the factory of event loop threads
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads, ThreadFactory threadFactory) {

        checkArgument(numThreads > 0, "numThreads: %s (expected: > 0)", numThreads);
        requireNonNull(threadFactory, "threadFactory");

        final TransportType type = Flags.transportType();
        return type.newEventLoopGroup(numThreads, unused -> threadFactory);
    }

    /**
     * Returns a special {@link EventLoop} which executes submitted tasks in the caller thread.
     * Note that this {@link EventLoop} will raise an {@link UnsupportedOperationException} for any operations
     * related with {@link EventLoop} shutdown or {@link Channel} registration.
     */
    public static EventLoop directEventLoop() {
        return directEventLoop;
    }

    /**
     * Returns the {@link ServerChannel} class that is available for this {@code eventLoopGroup}, for use in
     * configuring a custom {@link Bootstrap}.
     */
    public static Class<? extends ServerChannel> serverChannelType(EventLoopGroup eventLoopGroup) {
        return TransportType.serverChannelType(requireNonNull(eventLoopGroup, "eventLoopGroup"));
    }

    /**
     * Returns the available {@link SocketChannel} class for {@code eventLoopGroup}, for use in configuring a
     * custom {@link Bootstrap}.
     */
    public static Class<? extends SocketChannel> socketChannelType(EventLoopGroup eventLoopGroup) {
        return TransportType.socketChannelType(requireNonNull(eventLoopGroup, "eventLoopGroup"));
    }

    /**
     * Returns the available {@link DatagramChannel} class for {@code eventLoopGroup}, for use in configuring a
     * custom {@link Bootstrap}.
     */
    public static Class<? extends DatagramChannel> datagramChannelType(EventLoopGroup eventLoopGroup) {
        return TransportType.datagramChannelType(requireNonNull(eventLoopGroup, "eventLoopGroup"));
    }

    private EventLoopGroups() {}

    private static final class DirectEventLoop extends AbstractEventLoop {
        @Override
        public ChannelFuture register(Channel channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture register(ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelFuture register(Channel channel, ChannelPromise promise) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean inEventLoop(Thread thread) {
            return true;
        }

        @Override
        public boolean isShuttingDown() {
            return false;
        }

        @Override
        public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<?> terminationFuture() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return false;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public String toString() {
            return EventLoopGroups.class.getSimpleName() + ".directEventLoop()";
        }
    }
}
