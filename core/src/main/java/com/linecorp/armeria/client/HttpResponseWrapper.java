/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.google.common.base.MoreObjects.toStringHelper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.common.CancellationScheduler.CancellationTask;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

class HttpResponseWrapper implements StreamWriter<HttpObject> {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponseWrapper.class);

    @Nullable
    private final AbstractHttpRequestHandler requestHandler;
    private final DecodedHttpResponse delegate;
    private final EventLoop eventLoop;
    private final ClientRequestContext ctx;
    private final long maxContentLength;
    private final long responseTimeoutMillis;

    private boolean responseStarted;
    private long contentLengthHeaderValue = -1;

    private boolean done;
    private boolean closed;

    HttpResponseWrapper(@Nullable AbstractHttpRequestHandler requestHandler,
                        DecodedHttpResponse delegate, EventLoop eventLoop, ClientRequestContext ctx,
                        long responseTimeoutMillis, long maxContentLength) {
        this.requestHandler = requestHandler;
        this.delegate = delegate;
        this.eventLoop = eventLoop;
        this.ctx = ctx;
        this.maxContentLength = maxContentLength;
        this.responseTimeoutMillis = responseTimeoutMillis;
    }

    void handle100Continue(ResponseHeaders responseHeaders) {
        if (requestHandler != null) {
            requestHandler.handle100Continue(responseHeaders);
        }
    }

    DecodedHttpResponse delegate() {
        return delegate;
    }

    long maxContentLength() {
        return maxContentLength;
    }

    long writtenBytes() {
        return delegate.writtenBytes();
    }

    long contentLengthHeaderValue() {
        return contentLengthHeaderValue;
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long demand() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void abort() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void abort(Throwable cause) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryWrite(HttpObject o) {
        if (done) {
            PooledObjects.close(o);
            return false;
        }
        return delegate.tryWrite(o);
    }

    void startResponse() {
        if (responseStarted) {
            return;
        }
        responseStarted = true;
        ctx.logBuilder().startResponse();
        ctx.logBuilder().responseFirstBytesTransferred();
        initTimeout();
    }

    boolean tryWriteResponseHeaders(ResponseHeaders responseHeaders) {
        contentLengthHeaderValue = responseHeaders.contentLength();
        ctx.logBuilder().defer(RequestLogProperty.RESPONSE_HEADERS);
        try {
            return delegate.tryWrite(responseHeaders);
        } finally {
            ctx.logBuilder().responseHeaders(responseHeaders);
        }
    }

    boolean tryWriteData(HttpData data) {
        if (done) {
            PooledObjects.close(data);
            return false;
        }
        data.touch(ctx);
        ctx.logBuilder().increaseResponseLength(data);
        return delegate.tryWrite(data);
    }

    boolean tryWriteTrailers(HttpHeaders trailers) {
        if (done) {
            return false;
        }
        done = true;
        ctx.logBuilder().defer(RequestLogProperty.RESPONSE_TRAILERS);
        try {
            return delegate.tryWrite(trailers);
        } finally {
            ctx.logBuilder().responseTrailers(trailers);
        }
    }

    @Override
    public CompletableFuture<Void> whenConsumed() {
        return delegate.whenConsumed();
    }

    /**
     * This method is called when the delegate is completed.
     */
    void onSubscriptionCancelled(@Nullable Throwable cause) {
        close(cause, true);
    }

    @Override
    public void close() {
        close(null, false);
    }

    @Override
    public void close(Throwable cause) {
        close(cause, false);
    }

    void close(@Nullable Throwable cause, boolean cancel) {
        if (closed) {
            return;
        }
        done = true;
        closed = true;
        cancelTimeoutOrLog(cause, cancel);
        final HttpRequest request = ctx.request();
        assert request != null;
        if (cause != null) {
            request.abort(cause);
            return;
        }
        final long requestAutoAbortDelayMillis = ctx.requestAutoAbortDelayMillis();
        if (requestAutoAbortDelayMillis < 0 || requestAutoAbortDelayMillis == Long.MAX_VALUE) {
            return;
        }
        if (requestAutoAbortDelayMillis == 0) {
            request.abort(ResponseCompleteException.get());
            return;
        }
        ctx.eventLoop().schedule(() -> request.abort(ResponseCompleteException.get()),
                                 requestAutoAbortDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void closeAction(@Nullable Throwable cause) {
        if (cause != null) {
            delegate.close(cause);
            ctx.logBuilder().endResponse(cause);
        } else {
            delegate.close();
            ctx.logBuilder().endResponse();
        }
    }

    private void cancelAction(@Nullable Throwable cause) {
        if (cause != null && !(cause instanceof CancelledSubscriptionException)) {
            ctx.logBuilder().endResponse(cause);
        } else {
            ctx.logBuilder().endResponse();
        }
    }

    private void cancelTimeoutOrLog(@Nullable Throwable cause, boolean cancel) {
        CancellationScheduler responseCancellationScheduler = null;
        final ClientRequestContextExtension ctxExtension = ctx.as(ClientRequestContextExtension.class);
        if (ctxExtension != null) {
            responseCancellationScheduler = ctxExtension.responseCancellationScheduler();
        }

        if (responseCancellationScheduler == null || !responseCancellationScheduler.isFinished()) {
            if (responseCancellationScheduler != null) {
                responseCancellationScheduler.clearTimeout(false);
            }
            // There's no timeout or the response has not been timed out.
            if (cancel) {
                cancelAction(cause);
            } else {
                closeAction(cause);
            }
            return;
        }
        if (delegate.isOpen()) {
            closeAction(cause);
        }

        // Response has been timed out already.
        // Log only when it's not a ResponseTimeoutException.
        if (cause instanceof ResponseTimeoutException) {
            return;
        }

        if (cause == null || !logger.isWarnEnabled() || Exceptions.isExpected(cause)) {
            return;
        }

        final StringBuilder logMsg = new StringBuilder("Unexpected exception while closing a request");
        final String authority = ctx.request().authority();
        if (authority != null) {
            logMsg.append(" to ").append(authority);
        }

        logger.warn(logMsg.append(':').toString(), cause);
    }

    void initTimeout() {
        final ClientRequestContextExtension ctxExtension = ctx.as(ClientRequestContextExtension.class);
        if (ctxExtension != null) {
            final CancellationScheduler responseCancellationScheduler =
                    ctxExtension.responseCancellationScheduler();
            responseCancellationScheduler.start(newCancellationTask());
        }
    }

    private CancellationTask newCancellationTask() {
        return new CancellationTask() {
            @Override
            public boolean canSchedule() {
                return delegate.isOpen() && !done;
            }

            @Override
            public void run(Throwable cause) {
                delegate.close(cause);
                ctx.request().abort(cause);
                ctx.logBuilder().endResponse(cause);
            }
        };
    }

    @Override
    public String toString() {
        return toStringHelper(this).omitNullValues()
                                   .add("ctx", ctx)
                                   .add("eventLoop", eventLoop)
                                   .add("responseStarted", responseStarted)
                                   .add("maxContentLength", maxContentLength)
                                   .add("responseTimeoutMillis", responseTimeoutMillis)
                                   .add("contentLengthHeaderValue", contentLengthHeaderValue)
                                   .add("delegate", delegate)
                                   .toString();
    }
}
