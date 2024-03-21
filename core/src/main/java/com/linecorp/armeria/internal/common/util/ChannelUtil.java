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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSession;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.handler.ssl.SslHandler;

public final class ChannelUtil {

    private static final String CHANNEL_PACKAGE_NAME;
    @Nullable
    private static final String INCUBATOR_CHANNEL_PACKAGE_NAME;

    static {
        // Determine the names of the Netty packages.
        CHANNEL_PACKAGE_NAME = Channel.class.getPackage().getName();
        final int lastDotIndex = CHANNEL_PACKAGE_NAME.lastIndexOf('.');
        if (lastDotIndex > 0) {
            // CHANNEL_PACKAGE_NAME => INCUBATOR_CHANNEL_PACKAGE_NAME
            // ----------------------+-------------------------------
            // io.netty.channel      | io.netty.incubator.channel
            // shaded.netty.channel  | shaded.netty.incubator.channel
            INCUBATOR_CHANNEL_PACKAGE_NAME =
                    CHANNEL_PACKAGE_NAME.substring(0, lastDotIndex) + ".incubator.channel";
        } else {
            INCUBATOR_CHANNEL_PACKAGE_NAME = null;
        }
    }

    private static final Set<ChannelOption<?>> PROHIBITED_OPTIONS;
    private static final WriteBufferWaterMark DISABLED_WRITE_BUFFER_WATERMARK =
            new WriteBufferWaterMark(0, Integer.MAX_VALUE);
    @VisibleForTesting
    static final int TCP_USER_TIMEOUT_BUFFER_MILLIS = 5_000;

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
                    CHANNEL_PACKAGE_NAME + ".epoll.EpollChannelOption", false,
                    ChannelUtil.class.getClassLoader()).getField("EPOLL_MODE").get(null));
        } catch (Throwable ignored) {
            // Ignore
        }

        PROHIBITED_OPTIONS = builder.build();
    }

    @Nullable
    private static ChannelOption<Integer> epollTcpUserTimeout;
    @Nullable
    private static ChannelOption<Integer> epollTcpKeepidle;
    @Nullable
    private static ChannelOption<Integer> epollTcpKeepintvl;
    @Nullable
    private static ChannelOption<Integer> ioUringTcpUserTimeout;
    @Nullable
    private static ChannelOption<Integer> ioUringTcpKeepidle;
    @Nullable
    private static ChannelOption<Integer> ioUringTcpKeepintvl;

    private static final Set<ChannelOption<?>> tcpOptions;

    static {
        final ImmutableSet.Builder<ChannelOption<?>> tcpOptionsBuilder = ImmutableSet.builder();
        try {
            final Class<?> clazz = Class.forName(
                    CHANNEL_PACKAGE_NAME + ".epoll.EpollChannelOption", false,
                    ChannelUtil.class.getClassLoader());
            //noinspection unchecked
            epollTcpUserTimeout = (ChannelOption<Integer>) findChannelOption(clazz, "TCP_USER_TIMEOUT");
            //noinspection unchecked
            epollTcpKeepidle = (ChannelOption<Integer>) findChannelOption(clazz, "TCP_KEEPIDLE");
            //noinspection unchecked
            epollTcpKeepintvl = (ChannelOption<Integer>) findChannelOption(clazz, "TCP_KEEPINTVL");

            tcpOptionsBuilder.add(epollTcpUserTimeout);
            tcpOptionsBuilder.add(epollTcpKeepidle);
            tcpOptionsBuilder.add(epollTcpKeepintvl);
        } catch (Throwable ignored) {
            // Ignore
        }

        if (INCUBATOR_CHANNEL_PACKAGE_NAME != null) {
            try {
                final Class<?> clazz = Class.forName(
                        INCUBATOR_CHANNEL_PACKAGE_NAME + ".uring.IOUringChannelOption", false,
                        ChannelUtil.class.getClassLoader());
                //noinspection unchecked
                ioUringTcpUserTimeout = (ChannelOption<Integer>) findChannelOption(clazz, "TCP_USER_TIMEOUT");
                //noinspection unchecked
                ioUringTcpKeepidle = (ChannelOption<Integer>) findChannelOption(clazz, "TCP_KEEPIDLE");
                //noinspection unchecked
                ioUringTcpKeepintvl = (ChannelOption<Integer>) findChannelOption(clazz, "TCP_KEEPINTVL");

                tcpOptionsBuilder.add(ioUringTcpUserTimeout);
                tcpOptionsBuilder.add(ioUringTcpKeepidle);
                tcpOptionsBuilder.add(ioUringTcpKeepintvl);
            } catch (Throwable ignored) {
                // Ignore
            }
        }

        tcpOptions = tcpOptionsBuilder.build();
    }

    @Nullable
    private static ChannelOption<?> findChannelOption(Class<?> clazz, String fieldName) throws Throwable {
        try {
            final MethodHandle methodHandle = MethodHandles.publicLookup().findStaticGetter(
                    clazz, fieldName, ChannelOption.class);
            return (ChannelOption<?>) methodHandle.invokeExact();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Set<ChannelOption<?>> prohibitedOptions() {
        return PROHIBITED_OPTIONS;
    }

    public static CompletableFuture<Void> close(Iterable<? extends Channel> channels) {
        final List<Channel> channelsCopy = ImmutableList.copyOf(channels);
        if (channelsCopy.isEmpty()) {
            return UnmodifiableFuture.completedFuture(null);
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

    private static boolean canAddChannelOption(@Nullable ChannelOption<?> channelOption,
                                               Map<ChannelOption<?>, Object> channelOptions) {
        return channelOption != null && !channelOptions.containsKey(channelOption);
    }

    public static Map<ChannelOption<?>, Object> applyDefaultChannelOptions(
            Map<ChannelOption<?>, Object> channelOptions,
            long idleTimeoutMillis, long pingIntervalMillis) {
        return applyDefaultChannelOptions(
                Flags.useDefaultSocketOptions(), Flags.transportType(), channelOptions,
                idleTimeoutMillis, pingIntervalMillis);
    }

    @VisibleForTesting
    static Map<ChannelOption<?>, Object> applyDefaultChannelOptions(
            boolean enabled, TransportType transportType, Map<ChannelOption<?>, Object> channelOptions,
            long idleTimeoutMillis, long pingIntervalMillis) {
        if (!enabled) {
            return channelOptions;
        }

        final ImmutableMap.Builder<ChannelOption<?>, Object> newChannelOptionsBuilder = ImmutableMap.builder();

        if (idleTimeoutMillis > 0) {
            final int tcpUserTimeout = Ints.saturatedCast(idleTimeoutMillis + TCP_USER_TIMEOUT_BUFFER_MILLIS);
            if (transportType == TransportType.EPOLL &&
                canAddChannelOption(epollTcpUserTimeout, channelOptions)) {
                putChannelOption(newChannelOptionsBuilder, epollTcpUserTimeout, tcpUserTimeout);
            } else if (transportType == TransportType.IO_URING &&
                       canAddChannelOption(ioUringTcpUserTimeout, channelOptions)) {
                putChannelOption(newChannelOptionsBuilder, ioUringTcpUserTimeout, tcpUserTimeout);
            }
        }

        if (pingIntervalMillis > 0) {
            final int intPingIntervalMillis = Ints.saturatedCast(pingIntervalMillis);
            if (transportType == TransportType.EPOLL &&
                canAddChannelOption(epollTcpKeepidle, channelOptions) &&
                canAddChannelOption(epollTcpKeepintvl, channelOptions) &&
                canAddChannelOption(ChannelOption.SO_KEEPALIVE, channelOptions)) {
                putChannelOption(newChannelOptionsBuilder, ChannelOption.SO_KEEPALIVE, true);
                putChannelOption(newChannelOptionsBuilder, epollTcpKeepidle, intPingIntervalMillis);
                putChannelOption(newChannelOptionsBuilder, epollTcpKeepintvl, intPingIntervalMillis);
            } else if (transportType == TransportType.IO_URING &&
                       canAddChannelOption(ioUringTcpKeepidle, channelOptions) &&
                       canAddChannelOption(ioUringTcpKeepintvl, channelOptions) &&
                       canAddChannelOption(ChannelOption.SO_KEEPALIVE, channelOptions)) {
                putChannelOption(newChannelOptionsBuilder, ChannelOption.SO_KEEPALIVE, true);
                putChannelOption(newChannelOptionsBuilder, ioUringTcpKeepidle, intPingIntervalMillis);
                putChannelOption(newChannelOptionsBuilder, ioUringTcpKeepintvl, intPingIntervalMillis);
            }
        }
        newChannelOptionsBuilder.putAll(channelOptions);
        return newChannelOptionsBuilder.build();
    }

    private static <T> void putChannelOption(
            ImmutableMap.Builder<ChannelOption<?>, Object> newChannelOptionsBuilder,
            ChannelOption<T> channelOption, T value) {
        newChannelOptionsBuilder.put(channelOption, value);
    }

    public static String channelPackageName() {
        return CHANNEL_PACKAGE_NAME;
    }

    @Nullable
    public static String incubatorChannelPackageName() {
        return INCUBATOR_CHANNEL_PACKAGE_NAME;
    }

    public static boolean isTcpOption(ChannelOption<?> option) {
        return tcpOptions.contains(option);
    }

    @Nullable
    public static InetSocketAddress localAddress(@Nullable Channel ch) {
        if (ch == null) {
            return null;
        }

        if (ch instanceof DomainSocketChannel) {
            return findAddress((DomainSocketChannel) ch);
        } else {
            return (InetSocketAddress) ch.localAddress();
        }
    }

    @Nullable
    public static InetSocketAddress remoteAddress(@Nullable Channel ch) {
        if (ch == null) {
            return null;
        }

        if (ch instanceof DomainSocketChannel) {
            return findAddress((DomainSocketChannel) ch);
        } else {
            return (InetSocketAddress) ch.remoteAddress();
        }
    }

    @Nullable
    private static DomainSocketAddress findAddress(DomainSocketChannel ch) {
        final Channel parent = ch.parent();
        if (parent != null) {
            final DomainSocketAddress addr = toArmeriaDomainSocketAddress(
                    (io.netty.channel.unix.DomainSocketAddress) parent.localAddress());
            if (addr != null) {
                return addr;
            }
        }

        final DomainSocketAddress laddr = toArmeriaDomainSocketAddress(ch.localAddress());
        if (laddr != null) {
            return laddr;
        }

        return toArmeriaDomainSocketAddress(ch.remoteAddress());
    }

    @Nullable
    private static DomainSocketAddress toArmeriaDomainSocketAddress(
            @Nullable io.netty.channel.unix.DomainSocketAddress addr) {
        if (addr != null) {
            final String path = addr.path();
            if (!Strings.isNullOrEmpty(path)) {
                return DomainSocketAddress.of(path);
            }
        }
        return null;
    }

    public static int getPort(SocketAddress addr, int defaultValue) {
        if (addr instanceof InetSocketAddress) {
            assert !(addr instanceof DomainSocketAddress) : addr;
            return ((InetSocketAddress) addr).getPort();
        }
        return defaultValue;
    }

    private ChannelUtil() {}
}
