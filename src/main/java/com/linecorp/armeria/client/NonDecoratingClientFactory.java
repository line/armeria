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

import java.net.InetAddress;
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
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.NameResolver;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddresses;
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
     */
    protected NonDecoratingClientFactory() {
        this(SessionOptions.DEFAULT);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     */
    protected NonDecoratingClientFactory(SessionOptions options) {
        this(options, type -> {
            switch (type) {
                case NIO:
                    return new DefaultThreadFactory("armeria-client-nio", false);
                case EPOLL:
                    return new DefaultThreadFactory("armeria-client-epoll", false);
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

        baseBootstrap.channel(channelType());
        baseBootstrap.resolver(
                options.addressResolverGroup()
                       .orElseGet(DnsAddressResolverGroup5657::new));

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

        this.baseBootstrap = baseBootstrap;
        this.options = options;
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

    private static class DnsAddressResolverGroup5657 extends DnsAddressResolverGroup {

        DnsAddressResolverGroup5657() {
            super(datagramChannelType(), DnsServerAddresses.defaultAddresses());
        }

        @Override
        protected NameResolver<InetAddress> newNameResolver(
                EventLoop eventLoop, ChannelFactory<? extends DatagramChannel> channelFactory,
                DnsServerAddresses nameServerAddresses) throws Exception {

            final DnsNameResolverBuilder builder = new DnsNameResolverBuilder(eventLoop);
            builder.channelFactory(channelFactory);
            builder.nameServerAddresses(nameServerAddresses);
            if (Boolean.getBoolean("java.net.preferIPv4Stack")) {
                // Resolve IPv4 addresses only when -Djava.net.preferIPv4Stack is enabled,
                // because JDK will fail or refuse connecting to an IPv6 address at all.
                // See: https://github.com/netty/netty/issues/5657
                builder.resolvedAddressTypes(InternetProtocolFamily.IPv4);
            }
            return builder.build();
        }
    }
}
