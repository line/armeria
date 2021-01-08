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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.ssl.SslHandler;

public final class ChannelUtil {

    private static final Set<ChannelOption<?>> PROHIBITED_OPTIONS;
    private static final WriteBufferWaterMark DISABLED_WRITE_BUFFER_WATERMARK =
            new WriteBufferWaterMark(0, Integer.MAX_VALUE);

    static {
        // Do not accept 1) the options that may break Armeria and 2) the deprecated options.
        final ImmutableSet.Builder<ChannelOption<?>> builder = ImmutableSet.builder();
        //noinspection deprecation
        builder.add(
                ChannelOption.ALLOW_HALF_CLOSURE, ChannelOption.AUTO_READ,
                ChannelOption.AUTO_CLOSE, ChannelOption.MAX_MESSAGES_PER_READ,
                ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, ChannelOption.WRITE_BUFFER_LOW_WATER_MARK);

        try {
            // Use reflection, just in case a user excluded netty-transport-native-epoll from the dependencies.
            builder.add((ChannelOption<?>) Class.forName(
                    "io.netty.channel.epoll.EpollChannelOption", false,
                    ChannelUtil.class.getClassLoader()).getField("EPOLL_MODE").get(null));
        } catch (Exception e) {
            // Ignore
        }

        PROHIBITED_OPTIONS = builder.build();
    }

    public static Set<ChannelOption<?>> prohibitedOptions() {
        return PROHIBITED_OPTIONS;
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
