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

import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.EmptyArrays;

class HttpSessionHandler extends ChannelDuplexHandler {
    private static final String ARMERIA_USER_AGENT = "armeria client";

    private static final Exception CLOSED_SESSION_EXCEPTION = new ClosedSessionException();

    static {
        CLOSED_SESSION_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

    private final SessionProtocol sessionProtocol;
    private final boolean isMultiplex;
    private final WaitsHolder waitsHolder;

    HttpSessionHandler(SessionProtocol sessionProtocol) {
        this.sessionProtocol = requireNonNull(sessionProtocol);
        isMultiplex = sessionProtocol.isMultiplex();
        waitsHolder = isMultiplex ? new MultiplexWaitsHolder() : new SequentialWaitsHolder();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;

            final Promise<FullHttpResponse> promise = waitsHolder.poll(response);

            if (promise != null) {
                try {
                    if (HttpStatusClass.SUCCESS == response.status().codeClass()) {
                        promise.setSuccess(response.retain());
                    } else {
                        promise.setFailure(new InvalidResponseException(
                                "HTTP Response code: " + response.status()));
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            } else {
                //if promise not found, we just bypass message to next
                ctx.fireChannelRead(msg);
            }

            if (!isMultiplex && HttpHeaderValues.CLOSE.contentEqualsIgnoreCase(
                    response.headers().get(HttpHeaderNames.CONNECTION))) {
                ctx.close();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        markAllPaddingReponseFailed(CLOSED_SESSION_EXCEPTION);
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        //handler removed when session closed (refer HttpSession.close())
        markAllPaddingReponseFailed(CLOSED_SESSION_EXCEPTION);
    }

    public void deactivateSession() {
        markAllPaddingReponseFailed(CLOSED_SESSION_EXCEPTION);
    }

    void markAllPaddingReponseFailed(Throwable e) {
        final Collection<Promise<FullHttpResponse>> resultPromises = waitsHolder.getAll();
        waitsHolder.clear();
        if (!resultPromises.isEmpty()) {
            resultPromises.forEach(promise -> promise.tryFailure(e));
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

    private static interface WaitsHolder {
        Promise<FullHttpResponse> poll(FullHttpResponse response);

        void put(Invocation invocation, FullHttpRequest request);

        Collection<Promise<FullHttpResponse>> getAll();

        void clear();

        default int size() {
            return getAll().size();
        }

        default boolean isEmpty() {
            return getAll().isEmpty();
        }
    }

    private static class SequentialWaitsHolder implements WaitsHolder {
        private final Queue<Promise<FullHttpResponse>> requestExpectQueue;

        SequentialWaitsHolder() {
            requestExpectQueue = new LinkedList<>();
        }

        @Override
        public Promise<FullHttpResponse> poll(FullHttpResponse response) {
            return requestExpectQueue.poll();
        }

        @Override
        public void put(Invocation invocation, FullHttpRequest request) {
            requestExpectQueue.add(invocation.resultPromise());
        }

        @Override
        public Collection<Promise<FullHttpResponse>> getAll() {
            return requestExpectQueue;
        }

        @Override
        public void clear() {
            requestExpectQueue.clear();
        }
    }

    private static class MultiplexWaitsHolder implements WaitsHolder {
        private final IntObjectMap<Promise<FullHttpResponse>> resultExpectMap;
        private int streamId;

        MultiplexWaitsHolder() {
            resultExpectMap = new IntObjectHashMap<>();
            streamId = 1;
        }

        @Override
        public Promise<FullHttpResponse> poll(FullHttpResponse response) {
            int streamID = response.headers().getInt(ExtensionHeaderNames.STREAM_ID.text(), 0);
            return resultExpectMap.get(streamID);
        }

        @Override
        public void put(Invocation invocation, FullHttpRequest request) {
            int streamId = nextStreamID();
            request.headers().add(ExtensionHeaderNames.STREAM_ID.text(), streamIdToString(streamId));
            resultExpectMap.put(streamId, invocation.resultPromise());
        }

        @Override
        public Collection<Promise<FullHttpResponse>> getAll() {
            return resultExpectMap.values();
        }

        @Override
        public void clear() {
            resultExpectMap.clear();
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

        headers.set(HttpHeaderNames.HOST, ctx.host());
        headers.set(ExtensionHeaderNames.SCHEME.text(), sessionProtocol.uriText());
        headers.set(HttpHeaderNames.USER_AGENT, ARMERIA_USER_AGENT);
        headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        ByteBuf contentBuf = request.content();
        if (contentBuf != null && contentBuf.isReadable()) {
            headers.set(HttpHeaderNames.CONTENT_LENGTH, contentBuf.readableBytes());
        }

        //we allow a user can set content type and accept headers
        String mimeType = ctx.scheme().serializationFormat().mimeType();
        if (!headers.contains(HttpHeaderNames.CONTENT_TYPE)) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, mimeType);
        }
        if (!headers.contains(HttpHeaderNames.ACCEPT)) {
            headers.set(HttpHeaderNames.ACCEPT, mimeType);
        }
        invocation.options().get(ClientOption.HTTP_HEADERS).ifPresent(headers::add);

        return request;
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
