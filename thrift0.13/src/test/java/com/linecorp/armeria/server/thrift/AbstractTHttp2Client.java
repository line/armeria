/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.server.thrift;

import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Promise;

/**
 * An extremely simple Thrift-over-HTTP/2 client which sends and receives a single Thrift request/response
 * per connection.
 */
abstract class AbstractTHttp2Client extends TTransport {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    @Nullable
    private final SslContext sslCtx;
    private final URI uri;
    private final String host;
    private final int port;
    private final String path;
    private final HttpHeaders defaultHeaders;

    private TMemoryInputTransport in;
    private final TMemoryBuffer out = new TMemoryBuffer(128);

    AbstractTHttp2Client(String uriStr, HttpHeaders defaultHeaders) throws TTransportException {
        uri = URI.create(uriStr);
        this.defaultHeaders = defaultHeaders;

        int port;
        switch (uri.getScheme()) {
        case "http":
            port = uri.getPort();
            if (port < 0) {
                port = 80;
            }
            sslCtx = null;
            break;
        case "https":
            port = uri.getPort();
            if (port < 0) {
                port = 443;
            }

            try {
                sslCtx = SslContextBuilder.forClient()
                        .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .applicationProtocolConfig(new ApplicationProtocolConfig(
                                Protocol.ALPN,
                                // NO_ADVERTISE is currently the only mode supported by both OpenSsl and
                                // JDK providers.
                                SelectorFailureBehavior.NO_ADVERTISE,
                                // ACCEPT is currently the only mode supported by both OpenSsl and
                                // JDK providers.
                                SelectedListenerFailureBehavior.ACCEPT,
                                ApplicationProtocolNames.HTTP_2))
                        .build();
            } catch (SSLException e) {
                throw new TTransportException(TTransportException.UNKNOWN, e);
            }
            break;
        default:
            throw new IllegalArgumentException("unknown scheme: " + uri.getScheme());
        }

        final String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("host not specified: " + uriStr);
        }

        final String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("path not specified: " + uriStr);
        }

        this.host = host;
        this.port = port;
        this.path = path;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public void open() { }

    @Override
    public void close() {
        group.shutdownGracefully();
    }

    @Override
    public int read(byte[] buf, int off, int len) throws TTransportException {
        return in.read(buf, off, len);
    }

    @Override
    public int readAll(byte[] buf, int off, int len) throws TTransportException {
        return in.readAll(buf, off, len);
    }

    @Override
    public byte[] getBuffer() {
        return in.getBuffer();
    }

    @Override
    public int getBufferPosition() {
        return in.getBufferPosition();
    }

    @Override
    public int getBytesRemainingInBuffer() {
        return in.getBytesRemainingInBuffer();
    }

    @Override
    public void consumeBuffer(int len) {
        in.consumeBuffer(len);
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        out.write(buf, off, len);
    }

    @Override
    public void flush() throws TTransportException {
        final THttp2ClientInitializer initHandler = new THttp2ClientInitializer();

        final Bootstrap b = new Bootstrap();
        b.group(group);
        b.channel(NioSocketChannel.class);
        b.handler(initHandler);

        Channel ch = null;
        try {
            ch = b.connect(host, port).syncUninterruptibly().channel();
            final THttp2ClientHandler handler = initHandler.clientHandler;

            // Wait until HTTP/2 upgrade is finished.
            assertTrue(handler.settingsPromise.await(5, TimeUnit.SECONDS));
            handler.settingsPromise.get();

            // Send a Thrift request.
            final FullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, path,
                    Unpooled.wrappedBuffer(out.getArray(), 0, out.length()));
            request.headers().add(HttpHeaderNames.HOST, host);
            request.headers().set(ExtensionHeaderNames.SCHEME.text(), uri.getScheme());
            request.headers().add(defaultHeaders);
            ch.writeAndFlush(request).sync();

            // Wait until the Thrift response is received.
            assertTrue(handler.responsePromise.await(5, TimeUnit.SECONDS));
            final ByteBuf response = handler.responsePromise.get();

            // Pass the received Thrift response to the Thrift client.
            final byte[] array = new byte[response.readableBytes()];
            response.readBytes(array);
            in = new TMemoryInputTransport(array);
            response.release();
        } catch (Exception e) {
            throw new TTransportException(TTransportException.UNKNOWN, e);
        } finally {
            if (ch != null) {
                ch.close();
            }
        }
    }

    protected final TTransport delegate() {
        return in;
    }

    private final class THttp2ClientInitializer extends ChannelInitializer<SocketChannel> {

        THttp2ClientHandler clientHandler;

        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            final ChannelPipeline p = ch.pipeline();
            final Http2Connection conn = new DefaultHttp2Connection(false);
            final HttpToHttp2ConnectionHandler connHandler = new HttpToHttp2ConnectionHandlerBuilder()
                    .connection(conn)
                    .frameListener(new DelegatingDecompressorFrameListener(
                            conn,
                            new InboundHttp2ToHttpAdapterBuilder(conn)
                                    .maxContentLength(Integer.MAX_VALUE)
                                    .propagateSettings(true).build()))
                    .build();

            clientHandler = new THttp2ClientHandler(ch.eventLoop());

            if (sslCtx != null) {
                p.addLast(sslCtx.newHandler(p.channel().alloc()));
                p.addLast(connHandler);
                configureEndOfPipeline(p);
            } else {
                final HttpClientCodec sourceCodec = new HttpClientCodec();
                final HttpClientUpgradeHandler upgradeHandler = new HttpClientUpgradeHandler(
                        sourceCodec, new Http2ClientUpgradeCodec(connHandler), 65536);

                p.addLast(sourceCodec, upgradeHandler, new UpgradeRequestHandler());
            }
        }

        private void configureEndOfPipeline(ChannelPipeline p) {
            p.addLast(clientHandler);
        }

        /**
         * A handler that triggers the cleartext upgrade to HTTP/2 by sending an initial HTTP request.
         */
        private final class UpgradeRequestHandler extends ChannelInboundHandlerAdapter {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                final DefaultFullHttpRequest upgradeRequest =
                        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/");
                ctx.writeAndFlush(upgradeRequest);

                ctx.fireChannelActive();

                // Done with this handler, remove it from the pipeline.
                ctx.pipeline().remove(this);

                configureEndOfPipeline(ctx.pipeline());
            }
        }
    }

    static final class THttp2ClientHandler extends SimpleChannelInboundHandler<Object> {

        final Promise<Void> settingsPromise;
        final Promise<ByteBuf> responsePromise;

        THttp2ClientHandler(EventLoop loop) {
            settingsPromise = loop.newPromise();
            responsePromise = loop.newPromise();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Http2Settings) {
                settingsPromise.setSuccess(null);
                return;
            }

            if (msg instanceof FullHttpResponse) {
                final FullHttpResponse res = (FullHttpResponse) msg;
                final Integer streamId = res.headers().getInt(
                        HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
                if (streamId == null) {
                    responsePromise.tryFailure(new AssertionError("message without stream ID: " + msg));
                    return;
                }

                if (streamId == 1) {
                    // Response to the upgrade request, which is OK to ignore.
                    return;
                }

                if (streamId != 3) {
                    responsePromise.tryFailure(new AssertionError("unexpected stream ID: " + msg));
                    return;
                }

                responsePromise.setSuccess(res.content().retain());
                return;
            }

            throw new IllegalStateException("unexpected message type: " + msg.getClass().getName());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            responsePromise.tryFailure(cause);
        }
    }
}
