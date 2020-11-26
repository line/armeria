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

package com.linecorp.armeria.internal.common.util;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.handler.ssl.SslHandler;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;

public final class ChannelUtil {

    private static final Class<? extends EventLoopGroup> EPOLL_EVENT_LOOP_CLASS;
    private static final Class<? extends EventLoopGroup> IOURING_EVENT_LOOP_CLASS;

    static {
        try {
            //noinspection unchecked
            EPOLL_EVENT_LOOP_CLASS = (Class<? extends EventLoopGroup>)
                    Class.forName("io.netty.channel.epoll.EpollEventLoop", false,
                                  EpollEventLoopGroup.class.getClassLoader());
            //noinspection unchecked
            IOURING_EVENT_LOOP_CLASS = (Class<? extends EventLoopGroup>)
                    Class.forName("io.netty.incubator.channel.uring.IOUringEventLoop", false,
                                  IOUringEventLoopGroup.class.getClassLoader());
        } catch (Exception e) {
            throw new IllegalStateException("failed to locate EpollEventLoop class", e);
        }
    }

    private static final WriteBufferWaterMark DISABLED_WRITE_BUFFER_WATERMARK =
            new WriteBufferWaterMark(0, Integer.MAX_VALUE);

    public static Class<? extends EventLoopGroup> epollEventLoopClass() {
        return EPOLL_EVENT_LOOP_CLASS;
    }

    public static Class<? extends EventLoopGroup> ioUringEventLoopClass() {
        return IOURING_EVENT_LOOP_CLASS;
    }

    public static CompletableFuture<Void> close(Iterable<? extends Channel> channels) {
        final List<Channel> channelsCopy = ImmutableList.copyOf(channels);
        if (channelsCopy.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        final AtomicInteger numChannelsToClose = new AtomicInteger(channelsCopy.size());
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final ChannelFutureListener listener = unused -> {
            if (numChannelsToClose.decrementAndGet() == 0) {
                future.complete(null);
            }
        };

        for (Channel ch : channelsCopy) {
            ch.close().addListener(listener);
        }

        return future;
    }

    /**
     * Disables the write buffer water mark of the specified {@link Channel}, because we do not use this
     * feature at all and thus we do not want {@code channelWritabilityChanged} events triggered often.
     */
    public static void disableWriterBufferWatermark(Channel channel) {
        channel.config().setWriteBufferWaterMark(DISABLED_WRITE_BUFFER_WATERMARK);
    }

    /**
     * Finds the {@link SSLSession} of the current TLS connection.
     *
     * @return the {@link SSLSession} if found, or {@code null} if {@link SessionProtocol} is not TLS,
     *         the {@link SSLSession} is not found or {@link Channel} is {@code null}.
     */
    @Nullable
    public static SSLSession findSslSession(@Nullable Channel channel, SessionProtocol sessionProtocol) {
        if (!sessionProtocol.isTls()) {
            return null;
        }

        return findSslSession(channel);
    }

    /**
     * Finds the {@link SSLSession} of the current TLS connection.
     *
     * @return the {@link SSLSession} if found, or {@code null} if not found or {@link Channel} is {@code null}.
     */
    @Nullable
    public static SSLSession findSslSession(@Nullable Channel channel) {
        if (channel == null) {
            return null;
        }

        final SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
        return sslHandler != null ? sslHandler.engine().getSession() : null;
    }

    private ChannelUtil() {}
}
