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
package com.linecorp.armeria.internal;

import java.util.concurrent.ThreadFactory;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.Flags;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Native transport types.
 */
public enum TransportType {
    NIO(NioServerSocketChannel.class, NioSocketChannel.class, NioDatagramChannel.class,
        NioEventLoopGroup.class, NioEventLoopGroup::new),

    EPOLL(EpollServerSocketChannel.class, EpollSocketChannel.class, EpollDatagramChannel.class,
          EpollEventLoopGroup.class, EpollEventLoopGroup::new);

    private final Class<? extends ServerChannel> serverChannelClass;
    private final Class<? extends SocketChannel> socketChannelClass;
    private final Class<? extends DatagramChannel> datagramClass;
    private final Class<? extends EventLoopGroup> eventLoopGroupClass;
    private final BiFunction<Integer, ThreadFactory, ? extends EventLoopGroup> eventLoopGroupConstructor;

    TransportType(Class<? extends ServerChannel> serverChannelClass,
                  Class<? extends SocketChannel> socketChannelClass,
                  Class<? extends DatagramChannel> datagramClass,
                  Class<? extends EventLoopGroup> eventLoopGroupClass,
                  BiFunction<Integer, ThreadFactory, ? extends EventLoopGroup> eventLoopGroupConstructor) {
        this.serverChannelClass = serverChannelClass;
        this.socketChannelClass = socketChannelClass;
        this.datagramClass = datagramClass;
        this.eventLoopGroupClass = eventLoopGroupClass;
        this.eventLoopGroupConstructor = eventLoopGroupConstructor;
    }

    /**
     * Returns the {@link ServerChannel} class that is available for this transport type.
     */
    public Class<? extends ServerChannel> serverChannelClass() {
        return serverChannelClass;
    }

    /**
     * Creates the available {@link EventLoopGroup}.
     */
    public EventLoopGroup newEventLoopGroup(int nThreads,
                                            Function<TransportType, ThreadFactory> threadFactoryFactory) {
        ThreadFactory threadFactory = threadFactoryFactory.apply(this);
        return eventLoopGroupConstructor.apply(nThreads, threadFactory);
    }

    /**
     * Returns the available {@link TransportType}.
     */
    public static TransportType detectTransportType() {
        if (Flags.useEpoll()) {
            return EPOLL;
        } else {
            return NIO;
        }
    }

    /**
     * Returns the available {@link SocketChannel} class for {@code eventLoopGroup}.
     */
    public static Class<? extends SocketChannel> socketChannelType(EventLoopGroup eventLoopGroup) {
        for (TransportType type : values()) {
            if (type.eventLoopGroupClass.isAssignableFrom(eventLoopGroup.getClass())) {
                return type.socketChannelClass;
            }
        }
        throw unsupportedEventLoopType(eventLoopGroup);
    }

    /**
     * Returns the available {@link DatagramChannel} class for {@code eventLoopGroup}.
     */
    public static Class<? extends DatagramChannel> datagramChannelType(EventLoopGroup eventLoopGroup) {
        for (TransportType type : values()) {
            if (type.eventLoopGroupClass.isAssignableFrom(eventLoopGroup.getClass())) {
                return type.datagramClass;
            }
        }
        throw unsupportedEventLoopType(eventLoopGroup);
    }

    /**
     * Returns lowercase name of {@link TransportType}.
     * This method is a shortcut of:
     * <pre>{@code
     * Ascii.toLowerCase(name());
     * }</pre>
     */
    public String lowerCasedName() {
        return Ascii.toLowerCase(name());
    }

    private static IllegalStateException unsupportedEventLoopType(EventLoopGroup eventLoopGroup) {
        return new IllegalStateException("unsupported event loop type: " +
                                         eventLoopGroup.getClass().getName());
    }
}
