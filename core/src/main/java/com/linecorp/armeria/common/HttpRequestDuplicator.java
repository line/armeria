/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.stream.AbstractStreamMessageDuplicator;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageWrapper;

import io.netty.util.concurrent.EventExecutor;

/**
 * Allows subscribing to a {@link HttpRequest} multiple times by duplicating the stream.
 *
 * <pre>{@code
 * final HttpRequest originalReq = ...
 * final HttpRequestDuplicator reqDuplicator = new HttpRequestDuplicator(originalReq);
 *
 * final HttpRequest dupReq1 = reqDuplicator.duplicateStream();
 * final HttpRequest dupReq2 = reqDuplicator.duplicateStream(true); // the last stream
 *
 * dupReq1.subscribe(new FooSubscriber() {
 *     ...
 *     // Do something according to the first few elements of the request.
 * });
 *
 * final CompletableFuture<AggregatedHttpRequest> future2 = dupReq2.aggregate();
 * future2.handle((message, cause) -> {
 *     // Do something with message.
 * }
 * }</pre>
 */
public class HttpRequestDuplicator extends AbstractStreamMessageDuplicator<HttpObject, HttpRequest> {

    private final RequestHeaders headers;

    /**
     * Creates a new instance wrapping a {@link HttpRequest} and publishing to multiple subscribers.
     * The length of request is limited by default with the server-side parameter which is
     * {@link Flags#defaultMaxResponseLength()}. If you are at client-side, you need to use
     * {@link #HttpRequestDuplicator(HttpRequest, long)} and the {@code long} value should be greater than
     * the length of request or {@code 0} which disables the limit.
     * @param req the request that will publish data to subscribers
     */
    public HttpRequestDuplicator(HttpRequest req) {
        this(req, Flags.defaultMaxRequestLength());
    }

    /**
     * Creates a new instance wrapping a {@link HttpRequest} and publishing to multiple subscribers.
     * @param req the request that will publish data to subscribers
     * @param maxSignalLength the maximum length of signals. {@code 0} disables the length limit
     */
    public HttpRequestDuplicator(HttpRequest req, long maxSignalLength) {
        this(req, maxSignalLength, null);
    }

    /**
     * Creates a new instance wrapping a {@link HttpRequest} and publishing to multiple subscribers.
     * @param req the request that will publish data to subscribers
     * @param maxSignalLength the maximum length of signals. {@code 0} disables the length limit
     * @param executor the executor to use for upstream signals.
     */
    public HttpRequestDuplicator(HttpRequest req, long maxSignalLength, @Nullable EventExecutor executor) {
        super(requireNonNull(req, "req"), obj -> {
            if (obj instanceof HttpData) {
                return ((HttpData) obj).length();
            }
            return 0;
        }, executor, maxSignalLength);
        headers = req.headers();
    }

    @Override
    public HttpRequest duplicateStream() {
        return duplicateStream(headers);
    }

    @Override
    public HttpRequest duplicateStream(boolean lastStream) {
        return duplicateStream(headers, lastStream);
    }

    /**
     * Creates a new {@link HttpRequest} instance that publishes data from the {@code publisher} you create
     * this factory with.
     */
    public HttpRequest duplicateStream(RequestHeaders newHeaders) {
        return duplicateStream(newHeaders, false);
    }

    /**
     * Creates a new {@link HttpRequest} instance that publishes data from the {@code publisher} you create
     * this factory with. If you specify the {@code lastStream} as {@code true}, it will prevent further
     * creation of duplicate stream.
     */
    public HttpRequest duplicateStream(RequestHeaders newHeaders, boolean lastStream) {
        return new DuplicateHttpRequest(super.duplicateStream(lastStream), newHeaders);
    }

    private static class DuplicateHttpRequest
            extends StreamMessageWrapper<HttpObject> implements HttpRequest {

        private final RequestHeaders headers;

        DuplicateHttpRequest(StreamMessage<? extends HttpObject> delegate,
                             RequestHeaders headers) {
            super(delegate);
            this.headers = headers;
        }

        @Override
        public RequestHeaders headers() {
            return headers;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("headers", headers)
                              .toString();
        }
    }
}
