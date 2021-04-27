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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.DefaultHttpRequest;
import com.linecorp.armeria.internal.common.InboundTrafficController;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;

final class DecodedHttpRequest extends DefaultHttpRequest {

    private final EventLoop eventLoop;
    private final int id;
    private final int streamId;
    private final boolean keepAlive;
    private final InboundTrafficController inboundTrafficController;
    private final long maxRequestLength;
    @Nullable
    private ServiceRequestContext ctx;
    private long transferredBytes;

    @Nullable
    private HttpResponse response;
    private boolean isResponseAborted;

    DecodedHttpRequest(EventLoop eventLoop, int id, int streamId, RequestHeaders headers, boolean keepAlive,
                       InboundTrafficController inboundTrafficController, long maxRequestLength) {

        super(headers);

        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.keepAlive = keepAlive;
        this.inboundTrafficController = inboundTrafficController;
        this.maxRequestLength = maxRequestLength;
    }

    void init(ServiceRequestContext ctx) {
        this.ctx = ctx;
    }

    int id() {
        return id;
    }

    int streamId() {
        return streamId;
    }

    /**
     * Returns whether to keep the connection alive after this request is handled.
     */
    boolean isKeepAlive() {
        return keepAlive;
    }

    long maxRequestLength() {
        return ctx != null ? ctx.maxRequestLength() : maxRequestLength;
    }

    long transferredBytes() {
        return transferredBytes;
    }

    void increaseTransferredBytes(long delta) {
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
                inboundTrafficController.inc(httpData.length());
            }
            ctx.logBuilder().increaseRequestLength(httpData);
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

    /**
     * Sets the specified {@link HttpResponse} which responds to this request. This is always called
     * by the {@link HttpServerHandler} after the handler gets the {@link HttpResponse} from an
     * {@link HttpService}.
     */
    void setResponse(HttpResponse response) {
        if (isResponseAborted) {
            // This means that we already tried to close the request, so abort the response immediately.
            if (!response.isComplete()) {
                response.abort();
            }
        } else {
            this.response = response;
        }
    }

    /**
     * Aborts the {@link HttpResponse} which responds to this request if it exists.
     *
     * @see Http2RequestDecoder#onRstStreamRead(ChannelHandlerContext, int, long)
     */
    void abortResponse(Throwable cause, boolean cancel) {
        isResponseAborted = true;

        // Make sure to invoke the ServiceRequestContext.whenRequestCancelling() and whenRequestCancelled()
        // by cancelling a request
        if (cancel && ctx != null) {
            ctx.cancel(cause);
        }

        // Try to close the request first, then abort the response if it is already closed.
        if (!tryClose(cause) &&
            response != null && !response.isComplete()) {
            response.abort(cause);
        }
    }
}
