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

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.TransportTypeProvider;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;

/**
 * Native transport types.
 */
public enum TransportType {

    NIO(TransportTypeProvider.NIO),
    EPOLL(TransportTypeProvider.EPOLL),
    IO_URING(TransportTypeProvider.IO_URING);

    private final TransportTypeProvider provider;

    TransportType(TransportTypeProvider provider) {
        this.provider = provider;
    }

    /**
     * Returns the {@link ServerChannel} class for {@code eventLoopGroup}.
     *
     * @throws IllegalStateException if the specified {@link EventLoopGroup} is not supported or
     *                               its {@link TransportType} is not currently available.
     */
    public static Class<? extends ServerChannel> serverChannelType(EventLoopGroup eventLoopGroup) {
        return find(eventLoopGroup).serverChannelType();
    }

    /**
     * Returns the {@link ServerChannel} class that is available for this transport type.
     *
     * @throws IllegalStateException if this {@link TransportType} is not currently available.
     */
    public Class<? extends ServerChannel> serverChannelType() {
        return provider.serverChannelType();
    }

    /**
     * Returns the available {@link SocketChannel} class for {@code eventLoopGroup}.
     *
     * @throws IllegalStateException if the specified {@link EventLoopGroup} is not supported or
     *                               its {@link TransportType} is not currently available.
     */
    public static Class<? extends SocketChannel> socketChannelType(EventLoopGroup eventLoopGroup) {
        return find(eventLoopGroup).socketChannelType();
    }

    /**
     * Returns the {@link SocketChannel} class that is available for this transport type.
     *
     * @throws IllegalStateException if this {@link TransportType} is not currently available.
     */
    public Class<? extends SocketChannel> socketChannelType() {
        return provider.socketChannelType();
    }

    /**
     * Returns the available {@link DatagramChannel} class for {@code eventLoopGroup}.
     *
     * @throws IllegalStateException if the specified {@link EventLoopGroup} is not supported or
     *                               its {@link TransportType} is not currently available.
     */
    public static Class<? extends DatagramChannel> datagramChannelType(EventLoopGroup eventLoopGroup) {
        return find(eventLoopGroup).datagramChannelType();
    }

    /**
     * Returns the {@link DatagramChannel} class that is available for this transport type.
     *
     * @throws IllegalStateException if this {@link TransportType} is not currently available.
     */
    public Class<? extends DatagramChannel> datagramChannelType() {
        return provider.datagramChannelType();
    }

    /**
     * Returns whether the specified {@link EventLoopGroup} is supported by any of the currently available
     * {@link TransportType}s.
     */
    public static boolean isSupported(EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup instanceof EventLoop) {
            eventLoopGroup = ((EventLoop) eventLoopGroup).parent();
            if (eventLoopGroup == null) {
                return false;
            }
        }
        final TransportType found = findOrNull(eventLoopGroup);
        return found != null && found.isAvailable();
    }

    private static TransportType find(EventLoopGroup eventLoopGroup) {
        final TransportType found = findOrNull(eventLoopGroup);
        if (found == null) {
            throw unsupportedEventLoopType(eventLoopGroup);
        }
        return found;
    }

    @Nullable
    private static TransportType findOrNull(EventLoopGroup eventLoopGroup) {
        final Class<? extends EventLoopGroup> eventLoopGroupType = eventLoopGroup.getClass();
        for (TransportType type : values()) {
            if (type.isAvailable() &&
                (type.provider.eventLoopGroupType().isAssignableFrom(eventLoopGroupType) ||
                 type.provider.eventLoopType().isAssignableFrom(eventLoopGroupType))) {
                return type;
            }
        }
        return null;
    }

    /**
     * Returns lowercase name of {@link TransportType}.
     * This method is a shortcut for:
     * <pre>{@code
     * Ascii.toLowerCase(name());
     * }</pre>
     */
    public String lowerCasedName() {
        return Ascii.toLowerCase(name());
    }

    /**
     * Returns whether this {@link TransportType} is currently available.
     *
     * @see #unavailabilityCause()
     */
    public boolean isAvailable() {
        return unavailabilityCause() == null;
    }

    /**
     * Returns why this {@link TransportType} is not available.
     *
     * @return why this {@link TransportType} is not available, or {@code null} if this {@link TransportType}
     *         is currently available.
     *
     * @see #isAvailable()
     */
    @Nullable
    public Throwable unavailabilityCause() {
        return provider.unavailabilityCause();
    }

    /**
     * Creates the available {@link EventLoopGroup}.
     */
    public EventLoopGroup newEventLoopGroup(int nThreads,
                                            Function<TransportType, ThreadFactory> threadFactoryFactory) {
        final ThreadFactory threadFactory = threadFactoryFactory.apply(this);
        return provider.eventLoopGroupConstructor().apply(nThreads, threadFactory);
    }

    private static IllegalStateException unsupportedEventLoopType(EventLoopGroup eventLoopGroup) {
        return new IllegalStateException("unsupported event loop type: " +
                                         eventLoopGroup.getClass().getName());
    }
}
