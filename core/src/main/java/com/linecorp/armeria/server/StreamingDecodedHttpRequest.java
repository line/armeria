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

import javax.annotation.Nonnull;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.DefaultHttpRequest;
import com.linecorp.armeria.internal.common.InboundTrafficController;

import io.netty.channel.EventLoop;

final class StreamingDecodedHttpRequest extends DefaultHttpRequest implements DecodedHttpRequestWriter {

    private final EventLoop eventLoop;
    private final int id;
    private final int streamId;
    private final boolean keepAlive;
    private final InboundTrafficController inboundTrafficController;
    private final long maxRequestLength;
    private final RoutingContext routingCtx;
    private final ExchangeType exchangeType;
    private final long requestStartTimeNanos;
    private final long requestStartTimeMicros;
    private final boolean http1WebSocket;
    private boolean shouldResetOnlyIfRemoteIsOpen;

    @Nullable
    private ServiceRequestContext ctx;
    private long transferredBytes;

    @Nullable
    private HttpResponse response;
    @Nullable
    private Throwable abortResponseCause;

    private boolean isNormallyClosed;

    StreamingDecodedHttpRequest(EventLoop eventLoop, int id, int streamId, RequestHeaders headers,
                                boolean keepAlive, InboundTrafficController inboundTrafficController,
                                long maxRequestLength, RoutingContext routingCtx, ExchangeType exchangeType,
                                long requestStartTimeNanos, long requestStartTimeMicros,
                                boolean http1WebSocket, boolean shouldResetOnlyIfRemoteIsOpen) {
        super(headers);

        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.keepAlive = keepAlive;
        this.inboundTrafficController = inboundTrafficController;
        this.maxRequestLength = maxRequestLength;
        assert routingCtx.hasResult();
        this.routingCtx = routingCtx;
        this.exchangeType = exchangeType;
        this.requestStartTimeNanos = requestStartTimeNanos;
        this.requestStartTimeMicros = requestStartTimeMicros;
        this.http1WebSocket = http1WebSocket;
        this.shouldResetOnlyIfRemoteIsOpen = shouldResetOnlyIfRemoteIsOpen;
    }

    @Override
    public void init(ServiceRequestContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public RoutingContext routingContext() {
        return routingCtx;
    }

    @Nonnull
    @Override
    public Routed<ServiceConfig> route() {
        return routingCtx.result();
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public int streamId() {
        return streamId;
    }

    @Override
    public boolean isKeepAlive() {
        return keepAlive;
    }

    @Override
    public long maxRequestLength() {
        return ctx != null ? ctx.maxRequestLength() : maxRequestLength;
    }

    @Override
    public long transferredBytes() {
        return transferredBytes;
    }

    @Override
    public void increaseTransferredBytes(long delta) {
        if (transferredBytes > Long.MAX_VALUE - delta) {
            transferredBytes = Long.MAX_VALUE;
        } else {
            transferredBytes += delta;
        }
    }

    @Override
    public EventLoop defaultSubscriberExecutor() {
        return eventLoop;
    }

    @Override
    public boolean tryWrite(HttpObject obj) {
        assert ctx != null : "uninitialized DecodedHttpRequest must be aborted.";

        final boolean published;
        if (obj instanceof HttpHeaders) { // HTTP trailers.
            published = super.tryWrite(obj);
            ctx.logBuilder().requestTrailers((HttpHeaders) obj);
            // Close this stream because HTTP trailers is the last element of the request.
            close();
        } else {
            final HttpData httpData = (HttpData) obj;
            httpData.touch(ctx);
            published = super.tryWrite(httpData);
            if (published) {
                ctx.logBuilder().increaseRequestLength(httpData);
                inboundTrafficController.inc(httpData.length());
            }
            if (obj.isEndOfStream()) {
                close();
            }
        }

        return published;
    }

    @Override
    protected void onRemoval(HttpObject obj) {
        if (obj instanceof HttpData) {
            final int length = ((HttpData) obj).length();
            inboundTrafficController.dec(length);
        }
    }

    @Override
    public void close() {
        isNormallyClosed = true;
        super.close();
    }

    @Override
    public boolean isClosedSuccessfully() {
        return isNormallyClosed;
    }

    @Override
    public void setResponse(HttpResponse response) {
        if (abortResponseCause != null) {
            // This means that we already tried to close the request, so abort the response immediately.
            if (!response.isComplete()) {
                response.abort(abortResponseCause);
            }
        } else {
            this.response = response;
        }
    }

    @Override
    public void abortResponse(Throwable cause, boolean cancel) {
        if (abortResponseCause != null) {
            return;
        }
        abortResponseCause = cause;

        // Make sure to invoke the ServiceRequestContext.whenRequestCancelling() and whenRequestCancelled()
        // by cancelling a request
        if (cancel && ctx != null) {
            ctx.cancel(cause);
        }

        if (response != null && !response.isComplete()) {
            response.abort(cause);
        }
    }

    @Override
    public boolean isResponseAborted() {
        return abortResponseCause != null;
    }

    @Override
    public ExchangeType exchangeType() {
        return exchangeType;
    }

    @Override
    public long requestStartTimeNanos() {
        return requestStartTimeNanos;
    }

    @Override
    public long requestStartTimeMicros() {
        return requestStartTimeMicros;
    }

    @Override
    public boolean isHttp1WebSocket() {
        return http1WebSocket;
    }

    @Override
    public void setShouldResetOnlyIfRemoteIsOpen(boolean shouldResetOnlyIfRemoteIsOpen) {
        this.shouldResetOnlyIfRemoteIsOpen = shouldResetOnlyIfRemoteIsOpen;
    }

    @Override
    public boolean shouldResetOnlyIfRemoteIsOpen() {
        return shouldResetOnlyIfRemoteIsOpen;
    }
}
