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
 * Allows subscribing to a {@link HttpResponse} multiple times by duplicating the stream.
 *
 * <pre>{@code
 * > final HttpResponse originalRes = ...
 * > final HttpResponseDuplicator resDuplicator = new HttpResponseDuplicator(originalRes);
 * >
 * > final HttpResponse dupRes1 = resDuplicator.duplicateStream();
 * > final HttpResponse dupRes2 = resDuplicator.duplicateStream(true); // the last stream
 * >
 * > dupRes1.subscribe(new FooHeaderSubscriber() {
 * >     @Override
 * >     public void onNext(Object o) {
 * >     ...
 * >     // Do something according to the header's status.
 * >     }
 * > });
 * >
 * > dupRes2.aggregate().handle((aRes, cause) -> {
 * >     // Do something with the message.
 * > });
 * }</pre>
 */
public class HttpResponseDuplicator
        extends AbstractStreamMessageDuplicator<HttpObject, HttpResponse> {

    /**
     * Creates a new instance wrapping a {@link HttpResponse} and publishing to multiple subscribers.
     * The length of response is limited by default with the client-side parameter which is
     * {@link Flags#defaultMaxResponseLength()}. If you are at server-side, you need to use
     * {@link #HttpResponseDuplicator(HttpResponse, long)} and the {@code long} value should be greater than
     * the length of response or {@code 0} which disables the limit.
     * @param res the response that will publish data to subscribers
     */
    public HttpResponseDuplicator(HttpResponse res) {
        this(res, Flags.defaultMaxResponseLength());
    }

    /**
     * Creates a new instance wrapping a {@link HttpResponse} and publishing to multiple subscribers.
     * @param res the response that will publish data to subscribers
     * @param maxSignalLength the maximum length of signals. {@code 0} disables the length limit
     */
    public HttpResponseDuplicator(HttpResponse res, long maxSignalLength) {
        this(res, maxSignalLength, null);
    }

    /**
     * Creates a new instance wrapping a {@link HttpResponse} and publishing to multiple subscribers.
     * @param res the response that will publish data to subscribers
     * @param maxSignalLength the maximum length of signals. {@code 0} disables the length limit
     * @param executor the executor to use for upstream signals.
     */
    public HttpResponseDuplicator(HttpResponse res, long maxSignalLength, @Nullable EventExecutor executor) {
        super(requireNonNull(res, "res"), obj -> {
            if (obj instanceof HttpData) {
                return ((HttpData) obj).length();
            }
            return 0;
        }, executor, maxSignalLength);
    }

    @Override
    protected HttpResponse doDuplicateStream(StreamMessage<HttpObject> delegate) {
        return new DuplicateHttpResponse(delegate);
    }

    private static class DuplicateHttpResponse
            extends StreamMessageWrapper<HttpObject> implements HttpResponse {

        DuplicateHttpResponse(StreamMessage<? extends HttpObject> delegate) {
            super(delegate);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).toString();
        }
    }
}
