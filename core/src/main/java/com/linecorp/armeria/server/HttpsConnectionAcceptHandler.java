/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.SslContextFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.SniCompletionEvent;
import io.netty.handler.ssl.SslClientHelloHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * An {@link SslClientHelloHandler}-based alternative to Netty's {@code SniHandler} that also
 * runs the {@link ConnectionAcceptor} during the TLS lookup phase.
 */
final class HttpsConnectionAcceptHandler extends SslClientHelloHandler<SslContext> {

    private static final Logger logger = LoggerFactory.getLogger(HttpsConnectionAcceptHandler.class);

    // TLS extension types
    private static final int EXT_SERVER_NAME = 0x0000;
    private static final int EXT_ALPN = 0x0010;

    // Server name type for hostname
    private static final int SERVER_NAME_TYPE_HOSTNAME = 0;

    private final DefaultConnectionAcceptor connectionAcceptor;
    private final DefaultServerTlsProvider serverTlsProvider;
    @Nullable
    private final ProxiedAddresses proxiedAddresses;
    private final long handshakeTimeoutMillis;

    @Nullable
    private ScheduledFuture<?> timeoutFuture;
    @Nullable
    private String sniHostname;
    private final Promise<SslContext> lookupPromise;

    HttpsConnectionAcceptHandler(DefaultConnectionAcceptor connectionAcceptor,
                                 DefaultServerTlsProvider serverTlsProvider,
                                 @Nullable ProxiedAddresses proxiedAddresses,
                                 ChannelPipeline p, int maxClientHelloLength, long handshakeTimeoutMillis) {
        super(maxClientHelloLength);
        this.connectionAcceptor = connectionAcceptor;
        this.serverTlsProvider = serverTlsProvider;
        this.proxiedAddresses = proxiedAddresses;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        lookupPromise = p.channel().eventLoop().newPromise();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive()) {
            checkStartTimeout(ctx);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
        checkStartTimeout(ctx);
    }

    private void checkStartTimeout(ChannelHandlerContext ctx) {
        if (handshakeTimeoutMillis <= 0 || timeoutFuture != null) {
            return;
        }
        timeoutFuture = ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                lookupPromise.tryFailure(new DecoderException(new SslHandshakeTimeoutException(
                        "handshake timed out after " + handshakeTimeoutMillis + "ms")));
                // also close in case lookup isn't triggered yet
                ctx.close();
            }
        }, handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    protected Future<SslContext> lookup(ChannelHandlerContext ctx,
                                        @Nullable ByteBuf clientHello) throws Exception {
        if (lookupPromise.isDone()) {
            // channelInactive -> decodeLast can invoke lookup again for a closed connection
            return lookupPromise;
        }
        List<String> alpnProtocols = ImmutableList.of();

        if (clientHello != null) {
            final ClientHelloInfo info = parseClientHello(clientHello);
            sniHostname = info.sniHostname;
            if (info.alpnProtocols != null) {
                alpnProtocols = info.alpnProtocols;
            }
        }

        final ConnectionContext connectionCtx =
                new ConnectionContext(SessionProtocol.HTTPS, sniHostname, alpnProtocols,
                                      proxiedAddresses, ctx.channel());
        ctx.channel().attr(ConnectionContext.ATTR).set(connectionCtx);

        ctx.channel().closeFuture().addListener(f -> {
            // complete the promise so the clientHello is released
            lookupPromise.tryFailure(ClosedSessionException.get());
        });

        if (connectionAcceptor.isNoop()) {
            resolveSslContext(ctx, connectionCtx, lookupPromise);
        } else {
            connectionAcceptor.accept(connectionCtx, ctx.channel().eventLoop())
                              .whenComplete((accepted, t) -> {
                                  if (t != null) {
                                      lookupPromise.tryFailure(toSSLException(t));
                                      return;
                                  }
                                  if (accepted) {
                                      resolveSslContext(ctx, connectionCtx, lookupPromise);
                                  } else {
                                      logger.trace("Connection for '{}' rejected", connectionCtx);
                                      lookupPromise.trySuccess(null);
                                  }
                              });
        }

        return lookupPromise;
    }

    private void resolveSslContext(ChannelHandlerContext ctx,
                                   ConnectionContext connectionCtx,
                                   Promise<SslContext> promise) {
        serverTlsProvider.serverTlsSpec(connectionCtx, ctx.channel().eventLoop()).whenComplete((spec, t) -> {
            if (t != null) {
                promise.tryFailure(toSSLException(t));
                return;
            }
            try {
                final SslContextFactory factory = serverTlsProvider.sslContextFactory();
                final SslContext sslContext = factory.getOrCreate(spec);
                ctx.channel().closeFuture().addListener(f -> factory.release(sslContext));
                promise.trySuccess(sslContext);
            } catch (Exception e) {
                promise.tryFailure(toSSLException(e));
            }
        });
    }

    @Override
    protected void onLookupComplete(ChannelHandlerContext ctx,
                                    Future<SslContext> future) throws Exception {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        if (!ctx.channel().isActive()) {
            return;
        }

        final Throwable cause = future.cause();
        if (cause != null) {
            ctx.fireUserEventTriggered(new SniCompletionEvent(sniHostname, cause));
            ctx.fireExceptionCaught(cause);
            ctx.close();
            return;
        }

        final SslContext sslContext = future.getNow();
        if (sslContext == null) {
            // connection rejected via connectionacceptor
            ctx.close();
            return;
        }
        replaceHandler(ctx, sslContext);
        ctx.fireUserEventTriggered(new SniCompletionEvent(sniHostname));
    }

    private void replaceHandler(ChannelHandlerContext ctx, SslContext sslContext) {
        SslHandler sslHandler = null;
        try {
            sslHandler = sslContext.newHandler(ctx.alloc());
            if (handshakeTimeoutMillis > 0) {
                sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
            }
            ctx.pipeline().replace(this, SslHandler.class.getName(), sslHandler);
            sslHandler = null;
        } finally {
            if (sslHandler != null) {
                ReferenceCountUtil.safeRelease(sslHandler.engine());
            }
        }
    }

    // ------------------------------------------------------------------
    // Single-pass ClientHello parsing (SNI + ALPN)
    // See https://datatracker.ietf.org/doc/html/rfc5246#section-7.4.1.2
    //
    // struct {
    //    ProtocolVersion client_version;       // 2 bytes
    //    Random random;                        // 32 bytes
    //    SessionID session_id;                 // 1 byte length + variable
    //    CipherSuite cipher_suites;            // 2 bytes length + variable
    //    CompressionMethod compression_methods;// 1 byte length + variable
    //    Extension extensions;                 // 2 bytes length + variable
    // } ClientHello;
    // ------------------------------------------------------------------

    // Ported from AbstractSniHandler.extractSniHostname, extended to also extract ALPN.
    static ClientHelloInfo parseClientHello(ByteBuf in) {
        String sniHostname = null;
        List<String> alpnProtocols = null;

        int offset = in.readerIndex();
        final int endOffset = in.writerIndex();
        offset += 34;

        if (endOffset - offset >= 6) {
            final int sessionIdLength = in.getUnsignedByte(offset);
            offset += sessionIdLength + 1;

            final int cipherSuitesLength = in.getUnsignedShort(offset);
            offset += cipherSuitesLength + 2;

            final int compressionMethodLength = in.getUnsignedByte(offset);
            offset += compressionMethodLength + 1;

            final int extensionsLength = in.getUnsignedShort(offset);
            offset += 2;
            final int extensionsLimit = offset + extensionsLength;

            // Extensions should never exceed the record boundary.
            if (extensionsLimit <= endOffset) {
                while (extensionsLimit - offset >= 4) {
                    final int extensionType = in.getUnsignedShort(offset);
                    offset += 2;

                    final int extensionLength = in.getUnsignedShort(offset);
                    offset += 2;

                    if (extensionsLimit - offset < extensionLength) {
                        break;
                    }

                    if (extensionType == EXT_SERVER_NAME) {
                        sniHostname = parseSniExtension(in, offset, offset + extensionLength);
                    } else if (extensionType == EXT_ALPN) {
                        alpnProtocols = parseAlpnExtension(in, offset, offset + extensionLength);
                    }

                    offset += extensionLength;

                    // Early exit if we found both
                    if (sniHostname != null && alpnProtocols != null) {
                        break;
                    }
                }
            }
        }
        return new ClientHelloInfo(sniHostname, alpnProtocols);
    }

    // Ported from the inline SNI parsing in AbstractSniHandler.extractSniHostname.
    // See https://datatracker.ietf.org/doc/html/rfc6066#page-6
    @Nullable
    private static String parseSniExtension(ByteBuf in, int offset, int endOffset) {
        // server_name_list_length (2)
        if (endOffset - offset < 2) {
            return null;
        }
        offset += 2;

        if (endOffset - offset < 3) {
            return null;
        }

        final int serverNameType = in.getUnsignedByte(offset);
        offset++;

        if (serverNameType != SERVER_NAME_TYPE_HOSTNAME) {
            return null;
        }

        final int serverNameLength = in.getUnsignedShort(offset);
        offset += 2;

        if (endOffset - offset < serverNameLength) {
            return null;
        }

        return in.toString(offset, serverNameLength, CharsetUtil.US_ASCII)
                 .toLowerCase(Locale.US);
    }

    // See https://datatracker.ietf.org/doc/html/rfc7301#section-3.1
    @Nullable
    private static List<String> parseAlpnExtension(ByteBuf in, int offset, int endOffset) {
        // protocol_name_list_length (2)
        if (endOffset - offset < 2) {
            return null;
        }
        final int listLength = in.getUnsignedShort(offset);
        offset += 2;

        if (endOffset - offset < listLength) {
            return null;
        }
        final int listEnd = offset + listLength;

        final List<String> protocols = new ArrayList<>(4);
        while (listEnd - offset >= 1) {
            final int nameLength = in.getUnsignedByte(offset);
            offset++;
            if (listEnd - offset < nameLength) {
                break;
            }
            protocols.add(in.toString(offset, nameLength, CharsetUtil.US_ASCII));
            offset += nameLength;
        }

        return protocols.isEmpty() ? null : ImmutableList.copyOf(protocols);
    }

    static final class ClientHelloInfo {
        @Nullable
        final String sniHostname;
        @Nullable
        final List<String> alpnProtocols;

        ClientHelloInfo(@Nullable String sniHostname, @Nullable List<String> alpnProtocols) {
            this.sniHostname = sniHostname;
            this.alpnProtocols = alpnProtocols;
        }
    }

    static Throwable toSSLException(Throwable e) {
        Throwable cause = e.getCause();
        if (e instanceof DecoderException && cause instanceof SSLHandshakeException) {
            return e;
        }
        if (!(cause instanceof SSLHandshakeException)) {
            cause = new SSLHandshakeException(e.getMessage()).initCause(e);
        }

        return new DecoderException(cause);
    }
}
