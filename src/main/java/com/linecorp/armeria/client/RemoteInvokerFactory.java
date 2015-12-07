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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsServerAddresses;

/**
 * Creates and manages {@link RemoteInvoker}s.
 */
public final class RemoteInvokerFactory implements AutoCloseable {

    /**
     * The default {@link RemoteInvokerFactory} implementation.
     */
    public static final RemoteInvokerFactory DEFAULT = new RemoteInvokerFactory(RemoteInvokerOptions.DEFAULT);

    private final EventLoopGroup eventLoopGroup;
    private final boolean closeEventLoopGroup;
    private final Map<SessionProtocol, RemoteInvoker> remoteInvokers;

    /**
     * Creates a new instance with the specified {@link RemoteInvokerOptions}.
     */
    public RemoteInvokerFactory(RemoteInvokerOptions options) {
        requireNonNull(options, "options");

        final Bootstrap baseBootstrap = new Bootstrap();

        baseBootstrap.channel(channelType());
        baseBootstrap.resolver(new ConfiguredDnsNameResolverGroup());

        baseBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                             ConvertUtils.safeLongToInt(options.connectTimeoutMillis()));
        baseBootstrap.option(ChannelOption.SO_KEEPALIVE, true);

        final Optional<EventLoopGroup> eventLoopOption = options.eventLoopGroup();
        if (eventLoopOption.isPresent()) {
            eventLoopGroup = eventLoopOption.get();
            closeEventLoopGroup = false;
        } else {
            eventLoopGroup = createGroup();
            closeEventLoopGroup = true;
        }

        final EnumMap<SessionProtocol, RemoteInvoker> remoteInvokers = new EnumMap<>(SessionProtocol.class);
        final HttpRemoteInvoker remoteInvoker = new HttpRemoteInvoker(eventLoopGroup, baseBootstrap, options);

        SessionProtocol.ofHttp().stream().forEach(
                protocol -> remoteInvokers.put(protocol, remoteInvoker));

        this.remoteInvokers = Collections.unmodifiableMap(remoteInvokers);
    }

    private static Class<? extends SocketChannel> channelType() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    private static EventLoopGroup createGroup() {
        return Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
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
            return;
        }

        remoteInvokers.forEach((k, v) -> v.close());
        if (closeEventLoopGroup) {
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static final class ConfiguredDnsNameResolverGroup extends DnsNameResolverGroup {

        private static final DnsServerAddresses DEFAULT_DNS_SERVER_ADDRS;
        private static final int DEFAULT_MAX_TRIES;

        static {
            List<InetSocketAddress> defaultList = new ArrayList<>(DnsServerAddresses.defaultAddressList());
            List<InetSocketAddress> doubledList = new ArrayList<>(defaultList);

            // Add the same set of servers again, rotated left by 1 this time, so Netty does not always cycle
            // through the same servers. This works around: https://github.com/netty/netty/pull/4541
            // TODO(trustin): Remove the workaround and simplify when the upstream fix lands down.
            Collections.rotate(defaultList, 1);
            doubledList.addAll(defaultList);

            DEFAULT_DNS_SERVER_ADDRS = DnsServerAddresses.rotational(doubledList);
            DEFAULT_MAX_TRIES = doubledList.size();
        }

        ConfiguredDnsNameResolverGroup() {
            super(datagramChannelType(), DEFAULT_DNS_SERVER_ADDRS);
        }

        private static Class<? extends DatagramChannel> datagramChannelType() {
            return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
        }

        @Override
        protected DnsNameResolver newResolver(EventLoop eventLoop,
                                              ChannelFactory<? extends DatagramChannel> channelFactory,
                                              InetSocketAddress localAddress,
                                              DnsServerAddresses nameServerAddresses) throws Exception {

            final DnsNameResolver resolver =
                    new DnsNameResolver(eventLoop, channelFactory, localAddress, nameServerAddresses);

            return resolver.setMaxQueriesPerResolve(DEFAULT_MAX_TRIES);
        }
    }
}
