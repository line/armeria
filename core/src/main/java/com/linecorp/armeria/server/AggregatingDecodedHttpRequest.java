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

import javax.annotation.Nonnull;

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
    private final Routed<ServiceConfig> routed;
    @Nullable
    private ServiceRequestContext ctx;
    @Nullable
    private HttpHeaders trailers;
    private long transferredBytes;

    @Nullable
    private HttpResponse response;
    private boolean isResponseAborted;

    AggregatingDecodedHttpRequest(EventLoop eventLoop, int id, int streamId, RequestHeaders headers,
                                  boolean keepAlive, long maxRequestLength,
                                  RoutingContext routingCtx,
                                  Routed<ServiceConfig> routed) {
        super(4);
        this.headers = headers;
        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.keepAlive = keepAlive;
        this.maxRequestLength = maxRequestLength;
        this.routingCtx = routingCtx;
        this.routed = routed;
    }

    @Override
    public void init(ServiceRequestContext ctx) {
        this.ctx = ctx;
        ctx.logBuilder().increaseRequestLength(transferredBytes);
        if (trailers != null) {
            ctx.logBuilder().requestTrailers(trailers);
        }
    }

    @Override
    public RoutingContext routingContext() {
        return routingCtx;
    }

    @Nonnull
    @Override
    public Routed<ServiceConfig> route() {
        return routed;
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
        final boolean published = super.tryWrite(obj);

        if (obj instanceof HttpData) {
            ((HttpData) obj).touch(routingCtx);
            if (obj.isEndOfStream()) {
                close();
            }
        }
        if (obj instanceof HttpHeaders) {
            trailers = (HttpHeaders) obj;
            close();
        }
        return published;
    }

    @Override
    public void setResponse(HttpResponse response) {
        // TODO(ikhoon): Dedup
        if (isResponseAborted) {
            // This means that we already tried to close the request, so abort the response immediately.
            if (!response.isComplete()) {
                response.abort();
            }
        } else {
            this.response = response;
        }
    }

    @Override
    public void abortResponse(Throwable cause, boolean cancel) {
        isResponseAborted = true;

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
    public RequestHeaders headers() {
        return headers;
    }
}
