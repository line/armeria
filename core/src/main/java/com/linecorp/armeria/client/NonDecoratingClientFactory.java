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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.NativeLibraries;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * A skeletal {@link ClientFactory} that does not decorate other {@link ClientFactory}.
 */
public abstract class NonDecoratingClientFactory extends AbstractClientFactory {

    private enum TransportType {
        NIO, EPOLL
    }

    private final EventLoopGroup eventLoopGroup;
    private final boolean closeEventLoopGroup;
    private final SessionOptions options;
    private final Bootstrap baseBootstrap;

    // FIXME(trustin): Reuse idle connections instead of creating a new connection for every event loop.
    //                 Currently, when a client makes an invocation from a non-I/O thread, it simply chooses
    //                 an event loop using eventLoopGroup.next(). This makes the client factory to create as
    //                 many connections as the number of event loops. We don't really do this when there's an
    //                 idle connection established already regardless of its event loop.
    private final Supplier<EventLoop> eventLoopSupplier =
            () -> RequestContext.mapCurrent(RequestContext::eventLoop, () -> eventLoopGroup().next());

    /**
     * Creates a new instance with the default {@link SessionOptions}.
     *
     * @param useDaemonThreads whether to create I/O event loop threads as daemon threads
     */
    protected NonDecoratingClientFactory(boolean useDaemonThreads) {
        this(SessionOptions.DEFAULT, useDaemonThreads);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     *
     * @param useDaemonThreads whether to create I/O event loop threads as daemon threads
     */
    protected NonDecoratingClientFactory(SessionOptions options, boolean useDaemonThreads) {
        this(options, type -> {
            switch (type) {
                case NIO:
                    return new DefaultThreadFactory("armeria-client-nio", useDaemonThreads);
                case EPOLL:
                    return new DefaultThreadFactory("armeria-client-epoll", useDaemonThreads);
                default:
                    throw new Error();
            }
        });
    }

    private NonDecoratingClientFactory(SessionOptions options,
                                       Function<TransportType, ThreadFactory> threadFactoryFactory) {

        requireNonNull(options, "options");
        requireNonNull(threadFactoryFactory, "threadFactoryFactory");

        final Bootstrap baseBootstrap = new Bootstrap();

        final Optional<EventLoopGroup> eventLoopOption = options.eventLoopGroup();
        if (eventLoopOption.isPresent()) {
            eventLoopGroup = eventLoopOption.get();
            closeEventLoopGroup = false;
        } else {
            eventLoopGroup = createGroup(threadFactoryFactory);
            closeEventLoopGroup = true;
        }

        baseBootstrap.channel(channelType(eventLoopGroup));
        baseBootstrap.resolver(addressResolverGroup(options, eventLoopGroup));

        baseBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                             ConvertUtils.safeLongToInt(options.connectTimeoutMillis()));
        baseBootstrap.option(ChannelOption.SO_KEEPALIVE, true);

        this.baseBootstrap = baseBootstrap;
        this.options = options;
    }

    private static AddressResolverGroup<InetSocketAddress> addressResolverGroup(
            SessionOptions options, EventLoopGroup eventLoopGroup) {

        return options.addressResolverGroup().orElseGet(
                () -> new DnsAddressResolverGroup(
                        datagramChannelType(eventLoopGroup),
                        DnsServerAddressStreamProviders.platformDefault()));
    }

    private static Class<? extends SocketChannel> channelType(EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup instanceof NioEventLoopGroup) {
            return NioSocketChannel.class;
        }
        if (eventLoopGroup instanceof EpollEventLoopGroup) {
            return EpollSocketChannel.class;
        }
        throw unsupportedEventLoopType(eventLoopGroup);
    }

    private static Class<? extends DatagramChannel> datagramChannelType(EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup instanceof NioEventLoopGroup) {
            return NioDatagramChannel.class;
        }
        if (eventLoopGroup instanceof EpollEventLoopGroup) {
            return EpollDatagramChannel.class;
        }
        throw unsupportedEventLoopType(eventLoopGroup);
    }

    private static IllegalStateException unsupportedEventLoopType(EventLoopGroup eventLoopGroup) {
        return new IllegalStateException("unsupported event loop type: " +
                                         eventLoopGroup.getClass().getName());
    }

    @SuppressWarnings("checkstyle:operatorwrap")
    private static EventLoopGroup createGroup(Function<TransportType, ThreadFactory> threadFactoryFactory) {
        return NativeLibraries.isEpollAvailable()
               ? new EpollEventLoopGroup(0, threadFactoryFactory.apply(TransportType.EPOLL))
               : new NioEventLoopGroup(0, threadFactoryFactory.apply(TransportType.NIO));
    }

    @Override
    public final EventLoopGroup eventLoopGroup() {
        return eventLoopGroup;
    }

    @Override
    public final SessionOptions options() {
        return options;
    }

    /**
     * Returns a new {@link Bootstrap} whose {@link ChannelFactory}, {@link AddressResolverGroup} and
     * socket options are pre-configured.
     */
    protected Bootstrap newBootstrap() {
        return baseBootstrap.clone();
    }

    @Override
    public final Supplier<EventLoop> eventLoopSupplier() {
        return eventLoopSupplier;
    }

    @Override
    public void close() {
        if (closeEventLoopGroup) {
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
        }
    }
}
