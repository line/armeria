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
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.Promise;

class HttpSessionHandler extends ChannelDuplexHandler implements HttpSession {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionHandler.class);

    /**
     * 2^29 - We could have used 2^30 but this should be large enough.
     */
    private static final int MAX_NUM_REQUESTS_SENT = 536870912;

    private static final String ARMERIA_USER_AGENT = "armeria client";

    static HttpSession get(Channel ch) {
        if (!ch.isActive()) {
            return HttpSession.INACTIVE;
        }

        final HttpSessionHandler sessionHandler = ch.pipeline().get(HttpSessionHandler.class);
        if (sessionHandler == null) {
            return HttpSession.INACTIVE;
        }

        return sessionHandler;
    }

    private final SessionProtocol protocol;
    private final WaitsHolder waitsHolder;
    private volatile boolean active = true;
    private int numRequestsSent;

    HttpSessionHandler(SessionProtocol protocol) {
        this.protocol = requireNonNull(protocol);
        waitsHolder = protocol.isMultiplex() ? new MultiplexWaitsHolder() : new SequentialWaitsHolder();
    }

    @Override
    public SessionProtocol protocol() {
        return protocol;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public boolean onRequestSent() {
        return !isDisconnectionPending(++numRequestsSent);
    }

    private boolean isDisconnectionRequired(FullHttpResponse response) {
        if (isDisconnectionPending(numRequestsSent) && waitsHolder.isEmpty()) {
            return true;
        }

        // HTTP/1 request with the 'Connection: close' header
        return !protocol().isMultiplex() &&
               HttpHeaderValues.CLOSE.contentEqualsIgnoreCase(
                       response.headers().get(HttpHeaderNames.CONNECTION));
    }

    private static boolean isDisconnectionPending(int numRequestsSent) {
        return numRequestsSent >= MAX_NUM_REQUESTS_SENT;
    }

    @Override
    public void deactivate() {
        failPendingResponses(ClosedSessionException.INSTANCE);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2Settings) {
            // Expected
        } else if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;

            final Invocation invocation = waitsHolder.poll(response);

            if (invocation != null) {
                final ServiceInvocationContext iCtx = invocation.invocationContext();
                final SerializationFormat serializationFormat = iCtx.scheme().serializationFormat();
                try {
                    final Promise<FullHttpResponse> resultPromise = invocation.resultPromise();
                    if (HttpStatusClass.SUCCESS == response.status().codeClass()
                        // No serialization indicates a raw HTTP protocol which should
                        // have error responses returned.
                        || serializationFormat == SerializationFormat.NONE) {
                        iCtx.resolvePromise(resultPromise, response.retain());
                    } else {
                        final DecoderResult decoderResult = response.decoderResult();
                        final Throwable cause;

                        if (decoderResult.isSuccess()) {
                            cause = new InvalidResponseException("HTTP Response code: " + response.status());
                        } else {
                            final Throwable decoderCause = decoderResult.cause();
                            if (decoderCause instanceof DecoderException) {
                                cause = decoderCause;
                            } else {
                                cause = new DecoderException("protocol violation: " + decoderCause,
                                                             decoderCause);
                            }
                        }

                        iCtx.rejectPromise(resultPromise, cause);
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            } else {
                //if invocation not found, we just bypass message to next
                ctx.fireChannelRead(msg);
            }

            if (isDisconnectionRequired(response)) {
                ctx.close();
            }
        } else {
            try {
                throw new IllegalStateException("unexpected message type: " + msg);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        failPendingResponses(ClosedSessionException.INSTANCE);
        ctx.fireChannelInactive();
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Exceptions.logIfUnexpected(logger, ctx.channel(), cause);
        if (ctx.channel().isActive()) {
            ctx.close();
        }
    }

    private void failPendingResponses(Throwable e) {
        active = false;
        final Collection<Invocation> invocations = waitsHolder.getAll();
        if (!invocations.isEmpty()) {
            invocations.forEach(i -> i.invocationContext().rejectPromise(i.resultPromise(), e));
            waitsHolder.clear();
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof Invocation) {
            Invocation invocation = (Invocation) msg;
            FullHttpRequest request = convertToHttpRequest(invocation);
            waitsHolder.put(invocation, request);
            ctx.write(request, promise);
        } else {
            ctx.write(msg, promise);
        }
    }

    private interface WaitsHolder {
        Invocation poll(FullHttpResponse response);

        void put(Invocation invocation, FullHttpRequest request);

        Collection<Invocation> getAll();

        void clear();

        int size();

        boolean isEmpty();
    }

    private static class SequentialWaitsHolder implements WaitsHolder {
        private final Queue<Invocation> requestExpectQueue;

        SequentialWaitsHolder() {
            requestExpectQueue = new ArrayDeque<>();
        }

        @Override
        public Invocation poll(FullHttpResponse response) {
            return requestExpectQueue.poll();
        }

        @Override
        public void put(Invocation invocation, FullHttpRequest request) {
            requestExpectQueue.add(invocation);
        }

        @Override
        public Collection<Invocation> getAll() {
            return requestExpectQueue;
        }

        @Override
        public void clear() {
            requestExpectQueue.clear();
        }

        @Override
        public int size() {
            return requestExpectQueue.size();
        }

        @Override
        public boolean isEmpty() {
            return requestExpectQueue.isEmpty();
        }
    }

    private static class MultiplexWaitsHolder implements WaitsHolder {
        private final IntObjectMap<Invocation> resultExpectMap;
        private int streamId;

        MultiplexWaitsHolder() {
            resultExpectMap = new IntObjectHashMap<>();
            streamId = 1;
        }

        @Override
        public Invocation poll(FullHttpResponse response) {
            int streamID = response.headers().getInt(ExtensionHeaderNames.STREAM_ID.text(), 0);
            return resultExpectMap.remove(streamID);
        }

        @Override
        public void put(Invocation invocation, FullHttpRequest request) {
            int streamId = nextStreamID();
            request.headers().add(ExtensionHeaderNames.STREAM_ID.text(), streamIdToString(streamId));
            resultExpectMap.put(streamId, invocation);
        }

        @Override
        public Collection<Invocation> getAll() {
            return resultExpectMap.values();
        }

        @Override
        public void clear() {
            resultExpectMap.clear();
        }

        @Override
        public int size() {
            return resultExpectMap.size();
        }

        @Override
        public boolean isEmpty() {
            return resultExpectMap.isEmpty();
        }

        private static String streamIdToString(int streamID) {
            return Integer.toString(streamID);
        }

        private int nextStreamID() {
            return streamId += 2;
        }
    }

    private FullHttpRequest convertToHttpRequest(Invocation invocation) {
        requireNonNull(invocation, "invocation");
        final ServiceInvocationContext ctx = invocation.invocationContext();
        final FullHttpRequest request;
        final Object content = invocation.content();

        if (content instanceof FullHttpRequest) {
            request = (FullHttpRequest) content;
        } else if (content instanceof ByteBuf) {
            request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, ctx.path(),
                                                 (ByteBuf) content);
        } else {
            throw new IllegalStateException(
                    "content is not a ByteBuf or FullHttpRequest: " + content.getClass().getName());
        }

        HttpHeaders headers = request.headers();

        headers.set(HttpHeaderNames.HOST, hostHeader(ctx));
        headers.set(ExtensionHeaderNames.SCHEME.text(), protocol.uriText());
        headers.set(HttpHeaderNames.USER_AGENT, ARMERIA_USER_AGENT);
        headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        ByteBuf contentBuf = request.content();
        if (contentBuf != null && contentBuf.isReadable()) {
            headers.set(HttpHeaderNames.CONTENT_LENGTH, contentBuf.readableBytes());
        }

        invocation.options().get(ClientOption.HTTP_HEADERS).ifPresent(headers::add);
        if (ctx.scheme().serializationFormat() != SerializationFormat.NONE) {
            //we allow a user can set content type and accept headers
            String mimeType = ctx.scheme().serializationFormat().mimeType();
            if (!headers.contains(HttpHeaderNames.CONTENT_TYPE)) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, mimeType);
            }
            if (!headers.contains(HttpHeaderNames.ACCEPT)) {
                headers.set(HttpHeaderNames.ACCEPT, mimeType);
            }
        }

        return request;
    }

    private static String hostHeader(ServiceInvocationContext ctx) {
        final int port = ((InetSocketAddress) ctx.remoteAddress()).getPort();
        return HttpHostHeaderUtil.hostHeader(ctx.host(), port,
                                             ctx.scheme().sessionProtocol().isTls());
    }

    static class Invocation {
        private final ServiceInvocationContext invocationContext;
        private final Promise<FullHttpResponse> resultPromise;
        private final ClientOptions options;
        private final Object content;

        Invocation(ServiceInvocationContext invocationContext, ClientOptions options,
                   Promise<FullHttpResponse> resultPromise, Object content) {
            this.invocationContext = invocationContext;
            this.resultPromise = resultPromise;
            this.options = options;
            this.content = content;
        }

        ServiceInvocationContext invocationContext() {
            return invocationContext;
        }

        Promise<FullHttpResponse> resultPromise() {
            return resultPromise;
        }

        Object content() {
            return content;
        }

        ClientOptions options() {
            return options;
        }
    }
}
