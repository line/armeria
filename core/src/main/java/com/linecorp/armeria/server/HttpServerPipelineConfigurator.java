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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.ReadSuppressingHandler;
import com.linecorp.armeria.internal.common.TrafficLoggingHandler;
import com.linecorp.armeria.internal.common.util.ChannelUtil;
import com.linecorp.armeria.internal.server.ServerHttp1ObjectEncoder;

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
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
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
import io.netty.util.NetUtil;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * Configures Netty {@link ChannelPipeline} to serve HTTP/1 and 2 requests.
 */
final class HttpServerPipelineConfigurator extends ChannelInitializer<Channel> {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerPipelineConfigurator.class);

    private static final int SSL_RECORD_HEADER_LENGTH = 5;

    private static final AsciiString SCHEME_HTTP = AsciiString.cached("http");
    private static final AsciiString SCHEME_HTTPS = AsciiString.cached("https");

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
        ChannelUtil.disableWriterBufferWatermark(ch);

        final ChannelPipeline p = ch.pipeline();
        p.addLast(new FlushConsolidationHandler());
        p.addLast(ReadSuppressingHandler.INSTANCE);
        configurePipeline(p, port.protocols(), null);
    }

    private void configurePipeline(ChannelPipeline p, Set<SessionProtocol> protocols,
                                   @Nullable ProxiedAddresses proxiedAddresses) {
        if (protocols.size() == 1) {
            switch (protocols.iterator().next()) {
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

        final ScheduledFuture<?> protocolDetectionTimeoutFuture;
        // FIXME(trustin): Add a dedicated timeout option to ServerConfig.
        final long requestTimeoutMillis = config.defaultVirtualHost().requestTimeoutMillis();
        if (requestTimeoutMillis > 0) {
            // Close the connection if the protocol detection is not finished in time.
            final Channel ch = p.channel();
            protocolDetectionTimeoutFuture = ch.eventLoop().schedule(
                    (Runnable) ch::close, requestTimeoutMillis, TimeUnit.MILLISECONDS);
        } else {
            protocolDetectionTimeoutFuture = null;
        }

        p.addLast(new ProtocolDetectionHandler(protocols, proxiedAddresses, protocolDetectionTimeoutFuture));
    }

    private void configureHttp(ChannelPipeline p, @Nullable ProxiedAddresses proxiedAddresses) {
        final ServerHttp1ObjectEncoder responseEncoder = new ServerHttp1ObjectEncoder(
                p.channel(), SessionProtocol.H1C, config.isServerHeaderEnabled(), config.isDateHeaderEnabled());
        p.addLast(TrafficLoggingHandler.SERVER);
        p.addLast(new Http2PrefaceOrHttpHandler(responseEncoder));
        configureIdleTimeoutHandler(p, false);
        p.addLast(new HttpServerHandler(config, gracefulShutdownSupport, responseEncoder,
                                        SessionProtocol.H1C, proxiedAddresses));
    }

    private void configureIdleTimeoutHandler(ChannelPipeline p, boolean isHttp2) {
        if (config.idleTimeoutMillis() > 0) {
            p.addFirst(new HttpServerIdleTimeoutHandler(config.idleTimeoutMillis(), isHttp2,
                                                        config.http2PingTimeoutMillis() > 0));
        }
    }

    private void configureHttps(ChannelPipeline p, @Nullable ProxiedAddresses proxiedAddresses) {
        assert sslContexts != null;
        p.addLast(new SniHandler(sslContexts));
        p.addLast(TrafficLoggingHandler.SERVER);
        p.addLast(new Http2OrHttpHandler(proxiedAddresses));
    }

    private Http2ConnectionHandler newHttp2ConnectionHandler(ChannelPipeline pipeline, AsciiString scheme) {
        return new Http2ServerConnectionHandlerBuilder(pipeline.channel(), config,
                                                       gracefulShutdownSupport,
                                                       scheme.toString())
                .server(true)
                .initialSettings(http2Settings())
                .build();
    }

    private Http2Settings http2Settings() {
        final Http2Settings settings = new Http2Settings();
        final int initialWindowSize = config.http2InitialStreamWindowSize();
        if (initialWindowSize != Http2CodecUtil.DEFAULT_WINDOW_SIZE) {
            settings.initialWindowSize(initialWindowSize);
        }

        final int maxFrameSize = config.http2MaxFrameSize();
        if (maxFrameSize != Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE) {
            settings.maxFrameSize(maxFrameSize);
        }

        // Not using the value greater than 2^31-1 because some HTTP/2 client implementations use a signed
        // 32-bit integer to represent an HTTP/2 SETTINGS parameter value.
        settings.maxConcurrentStreams(Math.min(config.http2MaxStreamsPerConnection(), Integer.MAX_VALUE));
        settings.maxHeaderListSize(config.http2MaxHeaderListSize());
        return settings;
    }

    private final class ProtocolDetectionHandler extends ByteToMessageDecoder {

        private final EnumSet<SessionProtocol> candidates;
        @Nullable
        private final EnumSet<SessionProtocol> proxiedCandidates;
        @Nullable
        private final ProxiedAddresses proxiedAddresses;
        @Nullable
        private final ScheduledFuture<?> timeoutFuture;

        ProtocolDetectionHandler(Set<SessionProtocol> protocols, @Nullable ProxiedAddresses proxiedAddresses,
                                 @Nullable ScheduledFuture<?> timeoutFuture) {
            candidates = EnumSet.copyOf(protocols);
            if (protocols.contains(PROXY)) {
                proxiedCandidates = EnumSet.copyOf(candidates);
                proxiedCandidates.remove(PROXY);
            } else {
                proxiedCandidates = null;
            }
            this.proxiedAddresses = proxiedAddresses;
            this.timeoutFuture = timeoutFuture;
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

            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
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

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (!Exceptions.isExpected(cause)) {
                logger.warn("{} Unexpected exception while detecting the session protocol.", ctx, cause);
            }
            ctx.close();
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
            final InetAddress src = InetAddress.getByAddress(
                    NetUtil.createByteArrayFromIpAddressString(msg.sourceAddress()));
            final InetAddress dst = InetAddress.getByAddress(
                    NetUtil.createByteArrayFromIpAddressString(msg.destinationAddress()));
            final ProxiedAddresses proxiedAddresses =
                    ProxiedAddresses.of(new InetSocketAddress(src, msg.sourcePort()),
                                        new InetSocketAddress(dst, msg.destinationPort()));
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
            p.addLast(newHttp2ConnectionHandler(p, SCHEME_HTTPS));
            configureIdleTimeoutHandler(p, true);
            p.addLast(new HttpServerHandler(config, gracefulShutdownSupport, null,
                                            SessionProtocol.H2, proxiedAddresses));
        }

        private void addHttpHandlers(ChannelHandlerContext ctx) {
            final Channel ch = ctx.channel();
            final ChannelPipeline p = ctx.pipeline();
            final ServerHttp1ObjectEncoder writer = new ServerHttp1ObjectEncoder(
                    ch, SessionProtocol.H1, config.isServerHeaderEnabled(), config.isDateHeaderEnabled());
            p.addLast(new HttpServerCodec(
                    config.http1MaxInitialLineLength(),
                    config.http1MaxHeaderSize(),
                    config.http1MaxChunkSize()));
            p.addLast(new Http1RequestDecoder(config, ch, SCHEME_HTTPS, writer));
            configureIdleTimeoutHandler(p, false);
            p.addLast(new HttpServerHandler(config, gracefulShutdownSupport, writer,
                                            SessionProtocol.H1, proxiedAddresses));
        }

        @Override
        protected void handshakeFailure(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (!Exceptions.isExpected(cause)) {
                logger.warn("{} TLS handshake failed:", ctx.channel(), cause);
            }
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

        private final ServerHttp1ObjectEncoder responseEncoder;
        @Nullable
        private String name;

        Http2PrefaceOrHttpHandler(ServerHttp1ObjectEncoder responseEncoder) {
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
                    config.http1MaxInitialLineLength(),
                    config.http1MaxHeaderSize(),
                    config.http1MaxChunkSize());

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
                                newHttp2ConnectionHandler(p, SCHEME_HTTP));
                    },
                    UPGRADE_REQUEST_MAX_LENGTH));

            addAfter(p, baseName, new Http1RequestDecoder(config, ctx.channel(), SCHEME_HTTP, responseEncoder));
        }

        private void configureHttp2(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            assert name != null;
            addAfter(p, name, newHttp2ConnectionHandler(p, SCHEME_HTTP));
        }

        private String addAfter(ChannelPipeline p, String baseName, ChannelHandler handler) {
            p.addAfter(baseName, null, handler);
            return p.context(handler).name();
        }
    }
}
