/*
 * Copyright 2021 LINE Corporation
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

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.stream.AggregatingStreamMessage;

import io.netty.channel.EventLoop;

final class AggregatingDecodedHttpRequest extends AggregatingStreamMessage<HttpObject>
        implements DecodedHttpRequestWriter {

    private final EventLoop eventLoop;
    private final int id;
    private final int streamId;
    private final boolean keepAlive;
    private final long maxRequestLength;
    private final RequestHeaders headers;
    private final RoutingContext routingCtx;
    private final ExchangeType exchangeType;
    private final long requestStartTimeNanos;
    private final long requestStartTimeMicros;

    @Nullable
    private ServiceRequestContext ctx;
    private long transferredBytes;

    @Nullable
    private HttpResponse response;
    @Nullable
    private Throwable abortResponseCause;

    private boolean isNormallyClosed;

    private final CompletableFuture<Void> aggregationFuture = new CompletableFuture<>();

    AggregatingDecodedHttpRequest(EventLoop eventLoop, int id, int streamId, RequestHeaders headers,
                                  boolean keepAlive, long maxRequestLength,
                                  RoutingContext routingCtx, ExchangeType exchangeType,
                                  long requestStartTimeNanos, long requestStartTimeMicros) {
        super(4);
        this.headers = headers;
        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.keepAlive = keepAlive;
        this.maxRequestLength = maxRequestLength;
        assert routingCtx.hasResult();
        this.routingCtx = routingCtx;
        this.exchangeType = exchangeType;
        this.requestStartTimeNanos = requestStartTimeNanos;
        this.requestStartTimeMicros = requestStartTimeMicros;
    }

    @Override
    public void init(ServiceRequestContext ctx) {
        this.ctx = ctx;
    }

    @SuppressWarnings("unchecked")
    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregate(AggregationOptions options) {
        return super.aggregate(options);
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
        final boolean published = super.tryWrite(obj);

        if (obj instanceof HttpData) {
            final HttpData httpData = (HttpData) obj;
            httpData.touch(ctx);
            ctx.logBuilder().increaseRequestLength(httpData);
            if (obj.isEndOfStream()) {
                close();
            }
        }
        if (obj instanceof HttpHeaders) {
            ctx.logBuilder().requestTrailers((HttpHeaders) obj);
            close();
        }
        return published;
    }

    @Override
    public void setResponse(HttpResponse response) {
        // TODO(ikhoon): Dedup
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

        super.close(cause);
        // Make sure to invoke the ServiceRequestContext.whenRequestCancelling() and whenRequestCancelled()
        // by cancelling a request
        if (cancel && ctx != null) {
            ctx.cancel(cause);
        }

        // Complete aggregationFuture first to execute the aborted request with the service and decorators and
        // then abort the response.
        aggregationFuture.complete(null);
        if (response != null && !response.isComplete()) {
            response.abort(cause);
        }
    }

    @Override
    public void close() {
        isNormallyClosed = true;
        super.close();
        aggregationFuture.complete(null);
    }

    @Override
    public void close(Throwable cause) {
        super.close(cause);
        aggregationFuture.complete(null);
    }

    @Override
    public boolean isClosedSuccessfully() {
        return isNormallyClosed;
    }

    @Override
    public void abort() {
        super.abort();
        aggregationFuture.complete(null);
    }

    @Override
    public void abort(Throwable cause) {
        super.abort(cause);
        aggregationFuture.complete(null);
    }

    @Override
    public boolean isResponseAborted() {
        return abortResponseCause != null;
    }

    @Override
    public CompletableFuture<Void> whenAggregated() {
        return aggregationFuture;
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
    public RequestHeaders headers() {
        return headers;
    }
}
