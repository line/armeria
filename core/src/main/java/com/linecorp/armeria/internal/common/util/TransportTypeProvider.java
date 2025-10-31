/*
 * Copyright 2020 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.TransportType;

import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandle;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.channel.unix.ServerDomainSocketChannel;

/**
 * Provides the properties required by {@link TransportType} by loading /dev/epoll and io_uring transport
 * classes dynamically, so that Armeria does not have hard dependencies on them.
 * See: <a href="https://github.com/line/armeria/issues/3243">line/armeria#3243</a>
 */
public final class TransportTypeProvider {

    public static final TransportTypeProvider NIO = new TransportTypeProvider(
            "NIO", NioServerSocketChannel.class, NioSocketChannel.class, null, null, NioDatagramChannel.class,
            NioIoHandle.class, (nThreads, threadFactory) -> {
        return new MultiThreadIoEventLoopGroup(nThreads, threadFactory, NioIoHandler.newFactory());
    }, null);

    public static final TransportTypeProvider EPOLL = of(
            "EPOLL",
            ChannelUtil.channelPackageName(),
            ".epoll.Epoll",
            ".epoll.EpollServerSocketChannel",
            ".epoll.EpollSocketChannel",
            ".epoll.EpollServerDomainSocketChannel",
            ".epoll.EpollDomainSocketChannel",
            ".epoll.EpollDatagramChannel",
            ".epoll.EpollIoHandle",
            ".epoll.EpollIoHandler");

    public static final TransportTypeProvider KQUEUE = of(
            "KQUEUE",
            ChannelUtil.channelPackageName(),
            ".kqueue.KQueue",
            ".kqueue.KQueueServerSocketChannel",
            ".kqueue.KQueueSocketChannel",
            ".kqueue.KQueueServerDomainSocketChannel",
            ".kqueue.KQueueDomainSocketChannel",
            ".kqueue.KQueueDatagramChannel",
            ".kqueue.KQueueIoHandle",
            ".kqueue.KQueueIoHandler");

    public static final TransportTypeProvider IO_URING = of(
            "IO_URING",
            ChannelUtil.channelPackageName(),
            ".uring.IoUring",
            ".uring.IoUringServerSocketChannel",
            ".uring.IoUringSocketChannel",
            ".uring.IoUringServerDomainSocketChannel", ".uring.IoUringDomainSocketChannel",
            ".uring.IoUringDatagramChannel",
            ".uring.IoUringIoHandle",
            ".uring.IoUringIoHandler");

    private static TransportTypeProvider of(
            String name, @Nullable String channelPackageName, String entryPointTypeName,
            String serverSocketChannelTypeName, String socketChannelTypeName,
            @Nullable String domainServerSocketChannelTypeName, @Nullable String domainSocketChannelTypeName,
            String datagramChannelTypeName,
            String ioHandleTypeName, String ioHandlerTypeName) {

        if (channelPackageName == null) {
            return new TransportTypeProvider(
                    name, null, null, null, null, null, null, null,
                    new IllegalStateException("Failed to determine the shaded package name"));
        }

        // TODO(trustin): Do not try to load io_uring unless explicitly specified so JVM doesn't crash.
        //                https://github.com/netty/netty-incubator-transport-io_uring/issues/92
        if ("IO_URING".equals(name)) {
            if (!"io_uring".equals(Ascii.toLowerCase(
                    System.getProperty("com.linecorp.armeria.transportType", "")))) {
                return new TransportTypeProvider(
                        name, null, null, null, null, null, null, null,
                        new IllegalStateException("io_uring not enabled explicitly"));
            }
            // Require Java 9+ for io_uring transport.
            // See: https://github.com/netty/netty/pull/14962
            if (SystemInfo.javaVersion() < 9) {
                return new TransportTypeProvider(
                        name, null, null, null, null, null, null, null,
                        new IllegalStateException("Java 9+ is required to use " + name + " transport"));
            }
        }

        try {
            // Make sure the native libraries were loaded.
            final Throwable unavailabilityCause = (Throwable)
                    findClass(channelPackageName, entryPointTypeName)
                            .getMethod("unavailabilityCause")
                            .invoke(null);

            if (unavailabilityCause != null) {
                throw unavailabilityCause;
            }

            // Load the required classes and constructors.
            final Class<? extends ServerSocketChannel> ssc =
                    findClass(channelPackageName, serverSocketChannelTypeName);
            final Class<? extends SocketChannel> sc =
                    findClass(channelPackageName, socketChannelTypeName);

            final Class<? extends ServerDomainSocketChannel> sdsc;
            if (domainServerSocketChannelTypeName != null) {
                sdsc = findClass(channelPackageName, domainServerSocketChannelTypeName);
            } else {
                sdsc = null;
            }

            final Class<? extends DomainSocketChannel> dsc;
            if (domainSocketChannelTypeName != null) {
                dsc = findClass(channelPackageName, domainSocketChannelTypeName);
            } else {
                dsc = null;
            }

            final Class<? extends DatagramChannel> dc =
                    findClass(channelPackageName, datagramChannelTypeName);

            final Class<? extends IoHandle> ioHandleType =
                    findClass(channelPackageName, ioHandleTypeName);
            final Class<? extends IoHandler> ioHandlerType =
                    findClass(channelPackageName, ioHandlerTypeName);

            final BiFunction<Integer, ThreadFactory, ? extends IoEventLoopGroup> elgf =
                    findEventLoopGroupFactory(ioHandlerType);

            return new TransportTypeProvider(name, ssc, sc, sdsc, dsc, dc, ioHandleType, elgf, null);
        } catch (Throwable cause) {
            return new TransportTypeProvider(name, null, null, null, null, null, null, null,
                                             Exceptions.peel(cause));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> findClass(String channelPackageName, String className) throws Exception {
        return (Class<T>) Class.forName(channelPackageName + className, false,
                                        TransportTypeProvider.class.getClassLoader());
    }

    private static BiFunction<Integer, ThreadFactory, ? extends IoEventLoopGroup> findEventLoopGroupFactory(
            Class<? extends IoHandler> ioHandlerType) throws Exception {
        requireNonNull(ioHandlerType, "ioHandlerType");

        // Create a new IoHandlerFactory by invoking the factory methods in the ioHandlerType such as
        // EpollIoHandler.newFactory().
        final MethodHandle factoryMethod =
                MethodHandles.lookup().findStatic(ioHandlerType, "newFactory",
                                                  MethodType.methodType(IoHandlerFactory.class));
        return (nThreads, threadFactory) -> {
            try {
                final IoHandlerFactory ioHandlerFactory = (IoHandlerFactory) factoryMethod.invoke();
                return new MultiThreadIoEventLoopGroup(nThreads, threadFactory, ioHandlerFactory);
            } catch (Throwable t) {
                return Exceptions.throwUnsafely(Exceptions.peel(t));
            }
        };
    }

    private final String name;
    @Nullable
    private final Class<? extends ServerSocketChannel> serverChannelType;
    @Nullable
    private final Class<? extends SocketChannel> socketChannelType;
    @Nullable
    private final Class<? extends ServerDomainSocketChannel> domainServerChannelType;
    @Nullable
    private final Class<? extends DomainSocketChannel> domainSocketChannelType;
    @Nullable
    private final Class<? extends DatagramChannel> datagramChannelType;
    @Nullable
    private final Class<? extends IoHandle> ioHandleType;
    @Nullable
    private final BiFunction<Integer, ThreadFactory, ? extends IoEventLoopGroup> eventLoopGroupFactory;
    @Nullable
    private final Throwable unavailabilityCause;

    private TransportTypeProvider(
            String name,
            @Nullable
            Class<? extends ServerSocketChannel> serverChannelType,
            @Nullable
            Class<? extends SocketChannel> socketChannelType,
            @Nullable
            Class<? extends ServerDomainSocketChannel> domainServerChannelType,
            @Nullable
            Class<? extends DomainSocketChannel> domainSocketChannelType,
            @Nullable
            Class<? extends DatagramChannel> datagramChannelType,
            @Nullable
            Class<? extends IoHandle> ioHandleType,
            @Nullable
            BiFunction<Integer, ThreadFactory, ? extends IoEventLoopGroup> eventLoopGroupFactory,
            @Nullable
            Throwable unavailabilityCause) {

        assert (serverChannelType == null &&
                socketChannelType == null &&
                domainServerChannelType == null &&
                domainSocketChannelType == null &&
                datagramChannelType == null &&
                ioHandleType == null &&
                eventLoopGroupFactory == null &&
                unavailabilityCause != null) ||
               (serverChannelType != null &&
                socketChannelType != null &&
                datagramChannelType != null &&
                ioHandleType != null &&
                eventLoopGroupFactory != null &&
                unavailabilityCause == null);

        assert domainServerChannelType != null && domainSocketChannelType != null ||
               domainServerChannelType == null && domainSocketChannelType == null
                : domainServerChannelType + ", " + domainSocketChannelType;

        this.name = name;
        this.serverChannelType = serverChannelType;
        this.socketChannelType = socketChannelType;
        this.domainServerChannelType = domainServerChannelType;
        this.domainSocketChannelType = domainSocketChannelType;
        this.datagramChannelType = datagramChannelType;
        this.ioHandleType = ioHandleType;
        this.eventLoopGroupFactory = eventLoopGroupFactory;
        this.unavailabilityCause = unavailabilityCause;
    }

    public Class<? extends ServerSocketChannel> serverChannelType() {
        return ensureSupported(serverChannelType);
    }

    public Class<? extends SocketChannel> socketChannelType() {
        return ensureSupported(socketChannelType);
    }

    public boolean supportsDomainSockets() {
        return domainSocketChannelType != null;
    }

    public Class<? extends ServerDomainSocketChannel> domainServerChannelType() {
        return ensureSupported(domainServerChannelType);
    }

    public Class<? extends DomainSocketChannel> domainSocketChannelType() {
        return ensureSupported(domainSocketChannelType);
    }

    public Class<? extends DatagramChannel> datagramChannelType() {
        return ensureSupported(datagramChannelType);
    }

    public Class<? extends IoHandle> ioHandleType() {
        return ensureSupported(ioHandleType);
    }

    public BiFunction<Integer, ThreadFactory, ? extends IoEventLoopGroup> eventLoopGroupFactory() {
        return ensureSupported(eventLoopGroupFactory);
    }

    @Nullable
    public Throwable unavailabilityCause() {
        return unavailabilityCause;
    }

    private <T> T ensureSupported(@Nullable T value) {
        if (value == null) {
            throw new IllegalStateException(
                    "transport '" + name + "' not available: " + unavailabilityCause, unavailabilityCause);
        }
        return value;
    }
}
