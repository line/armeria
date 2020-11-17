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

import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.incubator.channel.uring.IOUringDatagramChannel;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringServerSocketChannel;
import io.netty.incubator.channel.uring.IOUringSocketChannel;

/**
 * Native transport types.
 */
public enum TransportType {

    NIO(NioServerSocketChannel.class, NioSocketChannel.class, NioDatagramChannel.class,
        NioEventLoopGroup::new, NioEventLoopGroup.class, NioEventLoop.class),

    EPOLL(EpollServerSocketChannel.class, EpollSocketChannel.class, EpollDatagramChannel.class,
          EpollEventLoopGroup::new, EpollEventLoopGroup.class, ChannelUtil.epollEventLoopClass()),

    IO_URING(IOUringServerSocketChannel.class, IOUringSocketChannel.class, IOUringDatagramChannel.class,
             IOUringEventLoopGroup::new, IOUringEventLoopGroup.class, ChannelUtil.ioUringEventLoopClass());

    private final Class<? extends ServerChannel> serverChannelType;
    private final Class<? extends SocketChannel> socketChannelType;
    private final Class<? extends DatagramChannel> datagramChannelType;
    private final Set<Class<? extends EventLoopGroup>> eventLoopGroupClasses;
    private final BiFunction<Integer, ThreadFactory, ? extends EventLoopGroup> eventLoopGroupConstructor;

    @SafeVarargs
    TransportType(Class<? extends ServerChannel> serverChannelType,
                  Class<? extends SocketChannel> socketChannelType,
                  Class<? extends DatagramChannel> datagramChannelType,
                  BiFunction<Integer, ThreadFactory, ? extends EventLoopGroup> eventLoopGroupConstructor,
                  Class<? extends EventLoopGroup>... eventLoopGroupClasses) {
        this.serverChannelType = serverChannelType;
        this.socketChannelType = socketChannelType;
        this.datagramChannelType = datagramChannelType;
        this.eventLoopGroupClasses = ImmutableSet.copyOf(eventLoopGroupClasses);
        this.eventLoopGroupConstructor = eventLoopGroupConstructor;
    }

    /**
     * Returns the available {@link TransportType}.
     */
    public static TransportType detectTransportType() {
        return Flags.transportType();
    }

    /**
     * Returns the {@link ServerChannel} class for {@code eventLoopGroup}.
     */
    public static Class<? extends ServerChannel> serverChannelType(EventLoopGroup eventLoopGroup) {
        return find(eventLoopGroup).serverChannelType;
    }

    /**
     * Returns the {@link ServerChannel} class that is available for this transport type.
     */
    public Class<? extends ServerChannel> serverChannelType() {
        return serverChannelType;
    }

    /**
     * Returns the available {@link SocketChannel} class for {@code eventLoopGroup}.
     */
    public static Class<? extends SocketChannel> socketChannelType(EventLoopGroup eventLoopGroup) {
        return find(eventLoopGroup).socketChannelType;
    }

    /**
     * Returns the available {@link DatagramChannel} class for {@code eventLoopGroup}.
     */
    public static Class<? extends DatagramChannel> datagramChannelType(EventLoopGroup eventLoopGroup) {
        return find(eventLoopGroup).datagramChannelType;
    }

    /**
     * Returns whether the specified {@link EventLoop} supports any {@link TransportType}.
     */
    public static boolean isSupported(EventLoop eventLoop) {
        final EventLoopGroup parent = eventLoop.parent();
        if (parent == null) {
            return false;
        }
        return isSupported(parent);
    }

    /**
     * Returns whether the specified {@link EventLoopGroup} supports any {@link TransportType}.
     */
    public static boolean isSupported(EventLoopGroup eventLoopGroup) {
        return findOrNull(eventLoopGroup) != null;
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
        for (TransportType type : values()) {
            for (Class<? extends EventLoopGroup> eventLoopGroupClass : type.eventLoopGroupClasses) {
                if (eventLoopGroupClass.isAssignableFrom(eventLoopGroup.getClass())) {
                    return type;
                }
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
     * Creates the available {@link EventLoopGroup}.
     */
    public EventLoopGroup newEventLoopGroup(int nThreads,
                                            Function<TransportType, ThreadFactory> threadFactoryFactory) {
        final ThreadFactory threadFactory = threadFactoryFactory.apply(this);
        return eventLoopGroupConstructor.apply(nThreads, threadFactory);
    }

    private static IllegalStateException unsupportedEventLoopType(EventLoopGroup eventLoopGroup) {
        return new IllegalStateException("unsupported event loop type: " +
                                         eventLoopGroup.getClass().getName());
    }
}
