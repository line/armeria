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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.NativeLibraries;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
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
import io.netty.handler.codec.dns.DatagramDnsResponseDecoder;
import io.netty.handler.codec.dns.DefaultDnsPtrRecord;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsRecordDecoder;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsServerAddresses;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * A skeletal {@link ClientFactory} that does not decorate other {@link ClientFactory}.
 */
public abstract class NonDecoratingClientFactory extends AbstractClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(NonDecoratingClientFactory.class);

    static {
        // TODO(trustin): Remove this hack once Netty 4.1.7.Final is out.
        // See https://github.com/netty/netty/pull/5923
        try {
            final Field decoder = DnsNameResolver.class.getDeclaredField("DECODER");
            final Field modifiers = Field.class.getDeclaredField("modifiers");

            // Trick the JDK by changing Field.modifier so that it allows updating a final field.
            modifiers.setAccessible(true);
            modifiers.setInt(decoder, decoder.getModifiers() & ~Modifier.FINAL);

            // Set DnsNameResolver.DECODER to a new decoder with a bug fix.
            decoder.setAccessible(true);
            decoder.set(null, new DatagramDnsResponseDecoder(new DefaultDnsRecordDecoder() {
                @Override
                protected DnsRecord decodeRecord(
                        String name, DnsRecordType type, int dnsClass, long timeToLive,
                        ByteBuf in, int offset, int length) throws Exception {

                    if (type == DnsRecordType.PTR) {
                        return new DefaultDnsPtrRecord(
                                name, dnsClass, timeToLive,
                                decodeName0(in.duplicate().setIndex(offset, offset + length)));
                    }
                    return new DefaultDnsRawRecord(
                            name, type, dnsClass, timeToLive,
                            in.retainedDuplicate().setIndex(offset, offset + length));
                }
            }));
        } catch (Exception e) {
            logger.warn("Failed to replace DnsNameResolver.DECODER. Some DNS resolutions may fail.", e);
        }
    }

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

        baseBootstrap.channel(channelType());
        baseBootstrap.resolver(
                options.addressResolverGroup()
                       .orElseGet(() -> new DnsAddressResolverGroup(datagramChannelType(),
                                                                    DnsServerAddresses.defaultAddresses())));

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
