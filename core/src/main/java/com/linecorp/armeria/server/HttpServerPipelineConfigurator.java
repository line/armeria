/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static com.linecorp.armeria.common.SessionProtocol.PROXY;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.Http1ObjectEncoder;
import com.linecorp.armeria.internal.Http2GoAwayListener;
import com.linecorp.armeria.internal.ReadSuppressingHandler;
import com.linecorp.armeria.internal.TrafficLoggingHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;
import io.netty.util.DomainNameMapping;

/**
 * Configures Netty {@link ChannelPipeline} to serve HTTP/1 and 2 requests.
 */
final class HttpServerPipelineConfigurator extends ChannelInitializer<Channel> {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerPipelineConfigurator.class);

    private static final int SSL_RECORD_HEADER_LENGTH = 5;

    private static final AsciiString SCHEME_HTTP = AsciiString.of("http");
    private static final AsciiString SCHEME_HTTPS = AsciiString.of("https");

    private static final int UPGRADE_REQUEST_MAX_LENGTH = 16384;

    private static final byte[] PROXY_V1_MAGIC_BYTES = {
            (byte) 'P', (byte) 'R', (byte) 'O', (byte) 'X', (byte) 'Y'
    };

    private static final byte[] PROXY_V2_MAGIC_BYTES = {
            (byte) 0x0D, (byte) 0x0A, (byte) 0x0D, (byte) 0x0A, (byte) 0x00, (byte) 0x0D, (byte) 0x0A,
            (byte) 0x51, (byte) 0x55, (byte) 0x49, (byte) 0x54, (byte) 0x0A
    };

    private final ServerConfig config;
    private final ServerPort port;
    @Nullable
    private final DomainNameMapping<SslContext> sslContexts;
    private final GracefulShutdownSupport gracefulShutdownSupport;

    /**
     * Creates a new instance.
     */
    HttpServerPipelineConfigurator(
            ServerConfig config, ServerPort port,
            @Nullable DomainNameMapping<SslContext> sslContexts,
            GracefulShutdownSupport gracefulShutdownSupport) {

        this.config = requireNonNull(config, "config");
        this.port = requireNonNull(port, "port");
        this.sslContexts = sslContexts;
        this.gracefulShutdownSupport = requireNonNull(gracefulShutdownSupport, "gracefulShutdownSupport");
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        final ChannelPipeline p = ch.pipeline();
        p.addLast(new FlushConsolidationHandler());
        p.addLast(ReadSuppressingHandler.INSTANCE);
        configurePipeline(p, port.protocols(), null);
    }

    private void configurePipeline(ChannelPipeline p, Set<SessionProtocol> protocols,
                                   @Nullable ProxiedAddresses proxiedAddresses) {
        if (protocols.size() == 1) {
            switch (Iterables.getFirst(protocols, null)) {
                case HTTP:
                    configureHttp(p, proxiedAddresses);
                    break;
                case HTTPS:
                    configureHttps(p, proxiedAddresses);
                    break;
                default:
                    // Should never reach here.
                    throw new Error();
            }
            return;
        }

        // More than one protocol were specified. Detect the protocol.
        p.addLast(new ProtocolDetectionHandler(protocols, proxiedAddresses));
    }

    private void configureHttp(ChannelPipeline p, @Nullable ProxiedAddresses proxiedAddresses) {
        final Http1ObjectEncoder responseEncoder = new Http1ObjectEncoder(p.channel(), true, false);
        p.addLast(TrafficLoggingHandler.SERVER);
        p.addLast(new Http2PrefaceOrHttpHandler(responseEncoder));
        configureIdleTimeoutHandler(p);
        p.addLast(new HttpServerHandler(config, gracefulShutdownSupport, responseEncoder,
                                        SessionProtocol.H1C, proxiedAddresses));
    }

    private void configureIdleTimeoutHandler(ChannelPipeline p) {
        if (config.idleTimeoutMillis() > 0) {
            p.addFirst(new HttpServerIdleTimeoutHandler(config.idleTimeoutMillis()));
        }
    }

    private void configureHttps(ChannelPipeline p, @Nullable ProxiedAddresses proxiedAddresses) {
        assert sslContexts != null;
        p.addLast(new SniHandler(sslContexts));
        p.addLast(TrafficLoggingHandler.SERVER);
        p.addLast(new Http2OrHttpHandler(proxiedAddresses));
    }

    private Http2ConnectionHandler newHttp2ConnectionHandler(ChannelPipeline pipeline) {

        final Http2Connection conn = new DefaultHttp2Connection(true);
        conn.addListener(new Http2GoAwayListener(pipeline.channel()));

        final Http2FrameReader reader = new DefaultHttp2FrameReader(true);
        final Http2FrameWriter writer = new DefaultHttp2FrameWriter();

        final Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(conn, writer);
        final Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(conn, encoder, reader);

        final Http2ConnectionHandler handler =
                new Http2ServerConnectionHandler(decoder, encoder, new Http2Settings());

        // Setup post build options
        final Http2RequestDecoder listener =
                new Http2RequestDecoder(config, pipeline.channel(), handler.encoder());

        handler.connection().addListener(listener);
        handler.decoder().frameListener(listener);
        handler.gracefulShutdownTimeoutMillis(config.idleTimeoutMillis());

        return handler;
    }

    private final class ProtocolDetectionHandler extends ByteToMessageDecoder {

        private final EnumSet<SessionProtocol> candidates;
        @Nullable
        private final EnumSet<SessionProtocol> proxiedCandidates;
        @Nullable
        private final ProxiedAddresses proxiedAddresses;

        ProtocolDetectionHandler(Set<SessionProtocol> protocols, @Nullable ProxiedAddresses proxiedAddresses) {
            candidates = EnumSet.copyOf(protocols);
            if (protocols.contains(PROXY)) {
                proxiedCandidates = EnumSet.copyOf(candidates);
                proxiedCandidates.remove(PROXY);
            } else {
                proxiedCandidates = null;
            }
            this.proxiedAddresses = proxiedAddresses;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            final int readableBytes = in.readableBytes();
            SessionProtocol detected = null;
            for (final Iterator<SessionProtocol> i = candidates.iterator(); i.hasNext();/* noop */) {
                final SessionProtocol protocol = i.next();
                switch (protocol) {
                    case HTTPS:
                        if (readableBytes < SSL_RECORD_HEADER_LENGTH) {
                            break;
                        }

                        if (SslHandler.isEncrypted(in)) {
                            detected = HTTPS;
                            break;
                        }

                        // Certainly not HTTPS.
                        i.remove();
                        break;
                    case PROXY:
                        // It's obvious that the magic bytes are longer in PROXY v2, but just in case.
                        assert PROXY_V1_MAGIC_BYTES.length < PROXY_V2_MAGIC_BYTES.length;

                        if (readableBytes < PROXY_V1_MAGIC_BYTES.length) {
                            break;
                        }

                        if (match(PROXY_V1_MAGIC_BYTES, in)) {
                            detected = PROXY;
                            break;
                        }

                        if (readableBytes < PROXY_V2_MAGIC_BYTES.length) {
                            break;
                        }

                        if (match(PROXY_V2_MAGIC_BYTES, in)) {
                            detected = PROXY;
                            break;
                        }

                        // Certainly not PROXY protocol.
                        i.remove();
                        break;
                }
            }

            if (detected == null) {
                if (candidates.size() == 1) {
                    // There's only one candidate left - HTTP.
                    detected = HTTP;
                } else {
                    // No protocol was detected and there are more than one candidate left.
                    return;
                }
            }

            final ChannelPipeline p = ctx.pipeline();
            switch (detected) {
                case HTTP:
                    configureHttp(p, proxiedAddresses);
                    break;
                case HTTPS:
                    configureHttps(p, proxiedAddresses);
                    break;
                case PROXY:
                    assert proxiedCandidates != null;
                    p.addLast(new HAProxyMessageDecoder(config.proxyProtocolMaxTlvSize()));
                    p.addLast(new ProxiedPipelineConfigurator(proxiedCandidates));
                    break;
                default:
                    // Never reaches here.
                    throw new Error();
            }
            p.remove(this);
        }

        private boolean match(byte[] prefix, ByteBuf buffer) {
            final int idx = buffer.readerIndex();
            for (int i = 0; i < prefix.length; i++) {
                final byte b = buffer.getByte(idx + i);
                if (b != prefix[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private final class ProxiedPipelineConfigurator extends MessageToMessageDecoder<HAProxyMessage> {

        private final EnumSet<SessionProtocol> proxiedCandidates;

        ProxiedPipelineConfigurator(EnumSet<SessionProtocol> proxiedCandidates) {
            this.proxiedCandidates = proxiedCandidates;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, HAProxyMessage msg, List<Object> out)
                throws Exception {
            if (logger.isDebugEnabled()) {
                logger.debug("PROXY message {}: {}:{} -> {}:{} (next: {})",
                             msg.protocolVersion().name(),
                             msg.sourceAddress(), msg.sourcePort(),
                             msg.destinationAddress(), msg.destinationPort(),
                             proxiedCandidates);
            }
            final ChannelPipeline p = ctx.pipeline();
            final ProxiedAddresses proxiedAddresses = ProxiedAddresses.of(
                    InetSocketAddress.createUnresolved(msg.sourceAddress(), msg.sourcePort()),
                    InetSocketAddress.createUnresolved(msg.destinationAddress(), msg.destinationPort()));
            configurePipeline(p, proxiedCandidates, proxiedAddresses);
            p.remove(this);
        }
    }

    private final class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler {

        @Nullable
        private final ProxiedAddresses proxiedAddresses;

        Http2OrHttpHandler(@Nullable ProxiedAddresses proxiedAddresses) {
            super(ApplicationProtocolNames.HTTP_1_1);
            this.proxiedAddresses = proxiedAddresses;
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                addHttp2Handlers(ctx);
                return;
            }

            if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
                addHttpHandlers(ctx);
                return;
            }

            throw new IllegalStateException("unknown protocol: " + protocol);
        }

        private void addHttp2Handlers(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            p.addLast(newHttp2ConnectionHandler(p));
            configureIdleTimeoutHandler(p);
            p.addLast(new HttpServerHandler(config, gracefulShutdownSupport, null,
                                            SessionProtocol.H2, proxiedAddresses));
        }

        private void addHttpHandlers(ChannelHandlerContext ctx) {
            final Channel ch = ctx.channel();
            final ChannelPipeline p = ctx.pipeline();
            final Http1ObjectEncoder writer = new Http1ObjectEncoder(ch, true, true);
            p.addLast(new HttpServerCodec(
                    config.defaultMaxHttp1InitialLineLength(),
                    config.defaultMaxHttp1HeaderSize(),
                    config.defaultMaxHttp1ChunkSize()));
            p.addLast(new Http1RequestDecoder(config, ch, SCHEME_HTTPS, writer));
            configureIdleTimeoutHandler(p);
            p.addLast(new HttpServerHandler(config, gracefulShutdownSupport, writer,
                                            SessionProtocol.H1, proxiedAddresses));
        }

        @Override
        protected void handshakeFailure(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.warn("{} TLS handshake failed:", ctx.channel(), cause);
            ctx.close();

            // On handshake failure, ApplicationProtocolNegotiationHandler will remove itself,
            // leaving no handlers behind it. Add a handler that handles the exceptions raised after this point.
            ctx.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                    if (cause instanceof DecoderException &&
                        cause.getCause() instanceof SSLException) {
                        // Swallow an SSLException raised after handshake failure.
                        return;
                    }

                    Exceptions.logIfUnexpected(logger, ctx.channel(), cause);
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            Exceptions.logIfUnexpected(logger, ctx.channel(), cause);
            ctx.close();
        }
    }

    private final class Http2PrefaceOrHttpHandler extends ByteToMessageDecoder {

        private final Http1ObjectEncoder responseEncoder;
        @Nullable
        private String name;

        Http2PrefaceOrHttpHandler(Http1ObjectEncoder responseEncoder) {
            this.responseEncoder = responseEncoder;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            super.handlerAdded(ctx);
            name = ctx.name();
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (in.readableBytes() < 4) {
                return;
            }

            if (in.getInt(in.readerIndex()) == 0x50524920) { // If starts with 'PRI '
                // Probably HTTP/2; received the HTTP/2 preface string.
                configureHttp2(ctx);
            } else {
                // Probably HTTP/1; the client can still upgrade using the traditional HTTP/1 upgrade request.
                configureHttp1WithUpgrade(ctx);
            }

            ctx.pipeline().remove(this);
        }

        private void configureHttp1WithUpgrade(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            final HttpServerCodec http1codec = new HttpServerCodec(
                    config.defaultMaxHttp1InitialLineLength(),
                    config.defaultMaxHttp1HeaderSize(),
                    config.defaultMaxHttp1ChunkSize());

            String baseName = name;
            assert baseName != null;
            baseName = addAfter(p, baseName, http1codec);
            baseName = addAfter(p, baseName, new HttpServerUpgradeHandler(
                    http1codec,
                    protocol -> {
                        if (!AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                            return null;
                        }

                        return new Http2ServerUpgradeCodec(
                                newHttp2ConnectionHandler(p));
                    },
                    UPGRADE_REQUEST_MAX_LENGTH));

            addAfter(p, baseName, new Http1RequestDecoder(config, ctx.channel(), SCHEME_HTTP, responseEncoder));
        }

        private void configureHttp2(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            assert name != null;
            addAfter(p, name, newHttp2ConnectionHandler(p));
        }

        private String addAfter(ChannelPipeline p, String baseName, ChannelHandler handler) {
            p.addAfter(baseName, null, handler);
            return p.context(handler).name();
        }
    }
}
