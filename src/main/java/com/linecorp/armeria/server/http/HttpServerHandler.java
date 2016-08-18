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

package com.linecorp.armeria.server.http;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.google.common.net.MediaType;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContext.PushHandle;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.ClosedPublisherException;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.http.AbstractHttp2ConnectionHandler;
import com.linecorp.armeria.internal.http.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.http.Http1ObjectEncoder;
import com.linecorp.armeria.internal.http.Http2ObjectEncoder;
import com.linecorp.armeria.internal.http.HttpObjectEncoder;
import com.linecorp.armeria.server.DefaultServiceRequestContext;
import com.linecorp.armeria.server.PathMapped;
import com.linecorp.armeria.server.ResourceNotFoundException;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceUnavailableException;
import com.linecorp.armeria.server.VirtualHost;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Settings;

final class HttpServerHandler extends ChannelInboundHandlerAdapter implements HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerHandler.class);

    private static final String ERROR_CONTENT_TYPE = MediaType.PLAIN_TEXT_UTF_8.toString();

    private static final Set<HttpMethod> ALLOWED_METHODS =
            Sets.immutableEnumSet(HttpMethod.DELETE, HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
                                  HttpMethod.PATCH, HttpMethod.POST, HttpMethod.PUT, HttpMethod.TRACE);

    private static final String ALLOWED_METHODS_STRING =
            ALLOWED_METHODS.stream().map(HttpMethod::name).collect(Collectors.joining(","));

    private static final Pattern PROHIBITED_PATH_PATTERN =
            Pattern.compile("(?:[:<>|?*\\\\]|/\\.\\.|\\.\\.$|\\.\\./|//+)");

    private static final ChannelFutureListener CLOSE = future -> {
        final Throwable cause = future.cause();
        final Channel ch = future.channel();
        if (cause != null) {
            Exceptions.logIfUnexpected(logger, ch, HttpServer.get(ch).protocol(), cause);
        }
        safeClose(ch);
    };

    static final ChannelFutureListener CLOSE_ON_FAILURE = future -> {
        final Throwable cause = future.cause();
        if (cause != null && !(cause instanceof ClosedPublisherException)) {
            final Channel ch = future.channel();
            Exceptions.logIfUnexpected(logger, ch, HttpServer.get(ch).protocol(), cause);
            safeClose(ch);
        }
    };

    static void safeClose(Channel ch) {
        if (!ch.isActive()) {
            return;
        }

        // Do not call Channel.close() if AbstractHttp2ConnectionHandler.close() has been invoked
        // already. Otherwise, it can trigger a bad cycle:
        //
        //   1. Channel.close() triggers AbstractHttp2ConnectionHandler.close().
        //   2. AbstractHttp2ConnectionHandler.close() triggers Http2Stream.close().
        //   3. Http2Stream.close() fails the promise of its pending writes.
        //   4. The failed promise notifies this listener (CLOSE_ON_FAILURE).
        //   5. This listener calls Channel.close().
        //   6. Repeat from step 1.
        //

        final AbstractHttp2ConnectionHandler h2handler =
                ch.pipeline().get(AbstractHttp2ConnectionHandler.class);

        if (h2handler == null || !h2handler.isClosing()) {
            ch.close();
        }
    }

    private final ServerConfig config;
    private SessionProtocol protocol;
    private HttpObjectEncoder responseEncoder;

    private int unfinishedRequests;
    private boolean isReading;
    private boolean handledLastRequest;

    HttpServerHandler(ServerConfig config, SessionProtocol protocol) {
        assert protocol == SessionProtocol.H1 ||
               protocol == SessionProtocol.H1C ||
               protocol == SessionProtocol.H2;

        this.config = requireNonNull(config, "config");
        this.protocol = requireNonNull(protocol, "protocol");

        if (protocol == SessionProtocol.H1 || protocol == SessionProtocol.H1C) {
            responseEncoder = new Http1ObjectEncoder(true);
        }
    }

    @Override
    public SessionProtocol protocol() {
        return protocol;
    }

    @Override
    public int unfinishedRequests() {
        return unfinishedRequests;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (responseEncoder != null) {
            responseEncoder.close();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        isReading = true; // Cleared in channelReadComplete()

        if (msg instanceof Http2Settings) {
            handleHttp2Settings(ctx, (Http2Settings) msg);
        } else {
            handleRequest(ctx, (DecodedHttpRequest) msg);
        }
    }

    private void handleHttp2Settings(ChannelHandlerContext ctx, Http2Settings h2settings) {
        if (h2settings.isEmpty()) {
            logger.trace("{} HTTP/2 settings: <empty>", ctx.channel());
        } else {
            logger.debug("{} HTTP/2 settings: {}", ctx.channel(), h2settings);
        }

        switch (protocol) {
            case H1:
                protocol = SessionProtocol.H2;
                break;
            case H1C:
                protocol = SessionProtocol.H2C;
                break;
        }

        final Http2ConnectionHandler handler = ctx.pipeline().get(Http2ConnectionHandler.class);
        if (responseEncoder != null) {
            responseEncoder.close();
        }
        responseEncoder = new Http2ObjectEncoder(handler.encoder());
    }

    private void handleRequest(ChannelHandlerContext ctx, DecodedHttpRequest req) throws Exception {
        // Ignore the request received after the last request,
        // because we are going to close the connection after sending the last response.
        if (handledLastRequest) {
            return;
        }

        // If we received the message with keep-alive disabled,
        // we should not accept a request anymore.
        if (!req.isKeepAlive()) {
            handledLastRequest = true;
        }

        final HttpHeaders headers = req.headers();
        if (!ALLOWED_METHODS.contains(headers.method())) {
            respond(ctx, req, HttpStatus.METHOD_NOT_ALLOWED);
            return;
        }

        // Handle 'OPTIONS * HTTP/1.1'.
        final String originalPath = headers.path();
        if (originalPath.isEmpty() || originalPath.charAt(0) != '/') {
            if (headers.method() == HttpMethod.OPTIONS && "*".equals(originalPath)) {
                handleOptions(ctx, req);
            } else {
                respond(ctx, req, HttpStatus.BAD_REQUEST);
            }
            return;
        }

        // Validate and split path and query.
        final String path = validatePathAndStripQuery(originalPath);
        if (path == null) {
            // Reject requests without a valid path.
            respond(ctx, req, HttpStatus.NOT_FOUND);
            return;
        }

        final String hostname = hostname(ctx, headers);
        final VirtualHost host = config.findVirtualHost(hostname);

        // Find the service that matches the path.
        final PathMapped<ServiceConfig> mapped = host.findServiceConfig(path);
        if (!mapped.isPresent()) {
            // No services matched the path.
            handleNonExistentMapping(ctx, req, host, path);
            return;
        }

        // Decode the request and create a new invocation context from it to perform an invocation.
        final String mappedPath = mapped.mappedPath();
        final ServiceConfig serviceCfg = mapped.value();
        final Service<Request, HttpResponse> service = serviceCfg.service();

        final Channel channel = ctx.channel();
        final ServiceRequestContext reqCtx = new DefaultServiceRequestContext(
                serviceCfg, channel,
                protocol,
                req.method().name(), path, mappedPath,
                LoggerFactory.getLogger(serviceCfg.loggerName()), req);

        final RequestLogBuilder reqLogBuilder = reqCtx.requestLogBuilder();
        final HttpResponse res;
        try (PushHandle ignored = RequestContext.push(reqCtx)) {
            req.init(reqCtx);
            res = service.serve(reqCtx, req);
        } catch (Throwable cause) {
            reqLogBuilder.end(cause);
            if (cause instanceof ResourceNotFoundException) {
                respond(ctx, req, HttpStatus.NOT_FOUND);
            } else if (cause instanceof ServiceUnavailableException) {
                respond(ctx, req, HttpStatus.SERVICE_UNAVAILABLE);
            } else {
                logger.warn("{} Unexpected exception: {}, {}", reqCtx, service, req, cause);
                respond(ctx, req, HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return;
        }

        final EventLoop eventLoop = channel.eventLoop();

        // Keep track of the number of unfinished requests and
        // clean up the request stream when response stream ends.
        unfinishedRequests++;
        res.closeFuture().handle(voidFunction((ret, cause) -> {
            req.abort();
            if (cause == null) {
                reqLogBuilder.end();
            } else {
                reqLogBuilder.end(cause);
            }
            eventLoop.execute(() -> {
                if (--unfinishedRequests == 0 && handledLastRequest) {
                    ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(CLOSE);
                }
            });
        })).exceptionally(CompletionActions::log);

        res.subscribe(new HttpResponseSubscriber(ctx, responseEncoder, reqCtx, req), eventLoop);
    }

    private void handleOptions(ChannelHandlerContext ctx, DecodedHttpRequest req) {
        respond(ctx, req,
                AggregatedHttpMessage.of(
                        HttpHeaders.of(HttpStatus.OK)
                                   .set(HttpHeaderNames.ALLOW, ALLOWED_METHODS_STRING)));
    }

    private void handleNonExistentMapping(ChannelHandlerContext ctx, DecodedHttpRequest req,
                                          VirtualHost host, String path) {

        if (path.charAt(path.length() - 1) != '/') {
            // Handle the case where /path doesn't exist but /path/ exists.
            final String pathWithSlash = path + '/';
            if (host.findServiceConfig(pathWithSlash).isPresent()) {
                final String location;
                final String originalPath = req.path();
                if (path.length() == originalPath.length()) {
                    location = pathWithSlash;
                } else {
                    location = pathWithSlash + originalPath.substring(path.length());
                }
                redirect(ctx, req, location);
                return;
            }
        }

        respond(ctx, req, HttpStatus.NOT_FOUND);
    }

    private String hostname(ChannelHandlerContext ctx, HttpHeaders headers) {
        final String hostname = headers.authority();
        if (hostname == null) {
            // Fill the authority with the default host name and current port, just in case the client did not
            // send it.
            final String defaultHostname = config.defaultVirtualHost().defaultHostname();
            final int port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
            headers.authority(defaultHostname + ':' + port);
            return defaultHostname;
        }

        final int hostnameColonIdx = hostname.lastIndexOf(':');
        if (hostnameColonIdx < 0) {
            return hostname;
        }

        return hostname.substring(0, hostnameColonIdx);
    }

    private static String validatePathAndStripQuery(String path) {
        // Filter out an empty path or a relative path.
        if (path.isEmpty() || path.charAt(0) != '/') {
            return null;
        }

        // Strip the query string.
        final int queryPos = path.indexOf('?');
        if (queryPos >= 0) {
            path = path.substring(0, queryPos);
        }

        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (IllegalArgumentException ignored) {
            // Malformed URL
            return null;
        } catch (UnsupportedEncodingException e) {
            // Should never happen
            throw new Error(e);
        }

        // Reject the prohibited patterns.
        if (PROHIBITED_PATH_PATTERN.matcher(path).find()) {
            return null;
        }

        return path;
    }

    private void redirect(ChannelHandlerContext ctx, DecodedHttpRequest req, String location) {
        respond(ctx, req,
                AggregatedHttpMessage.of(
                        HttpHeaders.of(HttpStatus.TEMPORARY_REDIRECT)
                                   .set(HttpHeaderNames.LOCATION, location)));
    }


    private void respond(ChannelHandlerContext ctx, DecodedHttpRequest req, HttpStatus status) {

        if (status.code() < 400) {
            respond(ctx, req, AggregatedHttpMessage.of(HttpHeaders.of(status)));
            return;
        }

        final HttpData content;
        if (req.method() == HttpMethod.HEAD || ArmeriaHttpUtil.isContentAlwaysEmpty(status)) {
            content = HttpData.EMPTY_DATA;
        } else {
            content = status.toHttpData();
        }

        respond(ctx, req,
                AggregatedHttpMessage.of(
                        HttpHeaders.of(status)
                                   .set(HttpHeaderNames.CONTENT_TYPE, ERROR_CONTENT_TYPE),
                        content));
    }

    private void respond(ChannelHandlerContext ctx, DecodedHttpRequest req, AggregatedHttpMessage res) {
        if (!handledLastRequest) {
            addKeepAliveHeaders(req, res);
            respond0(ctx, req, res).addListener(CLOSE_ON_FAILURE);
        } else {
            // Note that it is perfectly fine not to set the 'content-length' header to the last response
            // of an HTTP/1 connection. We set it anyway to work around overly strict HTTP clients that always
            // require a 'content-length' header for non-chunked responses.
            setContentLength(req, res);
            respond0(ctx, req, res).addListener(CLOSE);
        }

        if (!isReading) {
            ctx.flush();
        }
    }

    private ChannelFuture respond0(ChannelHandlerContext ctx, DecodedHttpRequest req, AggregatedHttpMessage res) {
        // No need to consume further since the response is ready.
        req.abort();

        final boolean trailingHeadersEmpty = res.trailingHeaders().isEmpty();
        final boolean contentAndTrailingHeadersEmpty = res.content().isEmpty() && trailingHeadersEmpty;
        ChannelFuture future = responseEncoder.writeHeaders(ctx, req.id(), req.streamId(), res.headers(), contentAndTrailingHeadersEmpty);
        if (!contentAndTrailingHeadersEmpty) {
            future = responseEncoder.writeData(ctx, req.id(), req.streamId(), res.content(), trailingHeadersEmpty);
            if (!trailingHeadersEmpty) {
                future = responseEncoder.writeHeaders(ctx, req.id(), req.streamId(), res.trailingHeaders(), true);
            }
        }
        return future;
    }

    /**
     * Sets the keep alive header as per:
     * - http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
     */
    private static void addKeepAliveHeaders(HttpRequest req, AggregatedHttpMessage res) {
        res.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        setContentLength(req, res);
    }

    /**
     * Sets the 'content-length' header to the response.
     */
    private static void setContentLength(HttpRequest req, AggregatedHttpMessage res) {
        // http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.4
        // prohibits to send message body for below cases.
        // and in those cases, content should be empty.
        if (req.method() == HttpMethod.HEAD || ArmeriaHttpUtil.isContentAlwaysEmpty(res.status())) {
            return;
        }
        res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, res.content().length());
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        isReading = false;
        ctx.flush();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        logger.warn("{} Unexpected user event: {}", ctx.channel(), evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Exceptions.logIfUnexpected(logger, ctx.channel(), protocol, cause);
        if (ctx.channel().isActive()) {
            ctx.close();
        }
    }
}
