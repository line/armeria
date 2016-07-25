/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.NativeLibraries;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddresses;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Creates and manages {@link RemoteInvoker}s.
 *
 * <h3>Life cycle of the default {@link RemoteInvokerFactory}</h3>
 * <p>
 * {@link Clients} or {@link ClientBuilder} uses {@link #DEFAULT}, the default {@link RemoteInvokerFactory},
 * unless you specified a {@link RemoteInvokerFactory} explicitly. Calling {@link #close()} on the default
 * {@link RemoteInvokerFactory} won't terminate its I/O threads and release other related resources unlike
 * other {@link RemoteInvokerFactory} to protect itself from accidental premature termination.
 * </p><p>
 * Instead, when the current {@link ClassLoader} is {@linkplain ClassLoader#getSystemClassLoader() the system
 * class loader}, a {@link Runtime#addShutdownHook(Thread) shutdown hook} is registered so that they are
 * released when the JVM exits.
 * </p><p>
 * If you are in an environment managed by a container or you desire the early termination of the default
 * {@link RemoteInvokerFactory}, use {@link #closeDefault()}.
 * </p>
 */
public final class RemoteInvokerFactory implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RemoteInvokerFactory.class);

    private enum TransportType {
        NIO, EPOLL
    }

    /**
     * The default {@link RemoteInvokerFactory} implementation.
     */
    public static final RemoteInvokerFactory DEFAULT = new RemoteInvokerFactory(
            RemoteInvokerOptions.DEFAULT,
            type -> {
                switch (type) {
                    case NIO:
                        return new DefaultThreadFactory("default-armeria-client-nio", true);
                    case EPOLL:
                        return new DefaultThreadFactory("default-armeria-client-epoll", true);
                    default:
                        throw new Error();
                }
            });

    /**
     * Closes the default {@link RemoteInvokerFactory}.
     */
    public static void closeDefault() {
        logger.debug("Closing the default {}", RemoteInvokerFactory.class.getSimpleName());
        DEFAULT.close0();
    }

    static {
        if (RemoteInvokerFactory.class.getClassLoader() == ClassLoader.getSystemClassLoader()) {
            Runtime.getRuntime().addShutdownHook(new Thread(RemoteInvokerFactory::closeDefault));
        }
    }

    private static final ThreadFactory DEFAULT_THREAD_FACTORY_NIO =
            new DefaultThreadFactory("armeria-client-nio", false);

    private static final ThreadFactory DEFAULT_THREAD_FACTORY_EPOLL =
            new DefaultThreadFactory("armeria-client-epoll", false);

    private final EventLoopGroup eventLoopGroup;
    private final boolean closeEventLoopGroup;
    private final Map<SessionProtocol, RemoteInvoker> remoteInvokers;

    /**
     * Creates a new instance with the specified {@link RemoteInvokerOptions}.
     */
    public RemoteInvokerFactory(RemoteInvokerOptions options) {
        this(options, type -> {
            switch (type) {
                case NIO:
                    return DEFAULT_THREAD_FACTORY_NIO;
                case EPOLL:
                    return DEFAULT_THREAD_FACTORY_EPOLL;
                default:
                    throw new Error();
            }
        });
    }

    private RemoteInvokerFactory(RemoteInvokerOptions options,
                                 Function<TransportType, ThreadFactory> threadFactoryFactory) {

        requireNonNull(options, "options");
        requireNonNull(threadFactoryFactory, "threadFactoryFactory");

        final Bootstrap baseBootstrap = new Bootstrap();

        baseBootstrap.channel(channelType());
        baseBootstrap.resolver(
                options.addressResolverGroup()
                       .orElseGet(() -> new DnsAddressResolverGroup(
                               datagramChannelType(), DnsServerAddresses.defaultAddresses())));

        baseBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                             ConvertUtils.safeLongToInt(options.connectTimeoutMillis()));
        baseBootstrap.option(ChannelOption.SO_KEEPALIVE, true);

        final Optional<EventLoopGroup> eventLoopOption = options.eventLoopGroup();
        if (eventLoopOption.isPresent()) {
            eventLoopGroup = eventLoopOption.get();
            closeEventLoopGroup = false;
        } else {
            eventLoopGroup = createGroup(threadFactoryFactory);
            closeEventLoopGroup = true;
        }

        final EnumMap<SessionProtocol, RemoteInvoker> remoteInvokers = new EnumMap<>(SessionProtocol.class);
        final HttpRemoteInvoker remoteInvoker = new HttpRemoteInvoker(baseBootstrap, options);

        SessionProtocol.ofHttp().forEach(
                protocol -> remoteInvokers.put(protocol, remoteInvoker));

        this.remoteInvokers = Collections.unmodifiableMap(remoteInvokers);
    }

    private static Class<? extends SocketChannel> channelType() {
        return NativeLibraries.isEpollAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    private static Class<? extends DatagramChannel> datagramChannelType() {
        return NativeLibraries.isEpollAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }

    private static EventLoopGroup createGroup(Function<TransportType, ThreadFactory> threadFactoryFactory) {
        return NativeLibraries.isEpollAvailable() ?
               new EpollEventLoopGroup(0, threadFactoryFactory.apply(TransportType.EPOLL)) :
               new NioEventLoopGroup(0, threadFactoryFactory.apply(TransportType.NIO));
    }

    /**
     * Returns the {@link EventLoopGroup} being used by this remote invoker factory. Can be used to, e.g.,
     * schedule a periodic task without creating a separate event loop.
     */
    public EventLoopGroup eventLoopGroup() {
        return eventLoopGroup;
    }

    /**
     * Returns a {@link RemoteInvoker} that can handle the specified {@link SessionProtocol}.
     */
    public RemoteInvoker getInvoker(SessionProtocol sessionProtocol) {
        final RemoteInvoker remoteInvoker = remoteInvokers.get(sessionProtocol);
        if (remoteInvoker == null) {
            throw new IllegalArgumentException("unsupported session protocol: " + sessionProtocol);
        }
        return remoteInvoker;
    }

    /**
     * Closes all {@link RemoteInvoker}s managed by this factory and shuts down the {@link EventLoopGroup}
     * created implicitly by this factory.
     */
    @Override
    public void close() {
        // The global default should never be closed.
        if (this == DEFAULT) {
            logger.debug("Refusing to close the default {}; must be closed via closeDefault()",
                         RemoteInvokerFactory.class.getSimpleName());
            return;
        }

        close0();
    }

    private void close0() {
        remoteInvokers.forEach((k, v) -> v.close());
        if (closeEventLoopGroup) {
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
        }
    }
}
