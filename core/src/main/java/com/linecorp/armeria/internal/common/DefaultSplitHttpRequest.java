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

package com.linecorp.armeria.internal.common;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SplitHttpRequest;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

public class DefaultSplitHttpRequest extends AbstractSplitHttpMessage implements SplitHttpRequest {

    private static final HttpHeaders EMPTY_TRAILERS = HttpHeaders.of();

    private final BodySubscriber bodySubscriber = new SplitHttpRequestBodySubscriber();
    private final HttpRequest request;

    public DefaultSplitHttpRequest(HttpRequest request, EventExecutor executor) {
        super(request, executor);
        this.request = request;
        request.subscribe(bodySubscriber, upstreamExecutor, SubscriptionOption.values());
    }

    @Override
    public RequestHeaders headers() {
        return request.headers();
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        subscribe0(subscriber, bodySubscriber, executor, options);
    }

    private final class SplitHttpRequestBodySubscriber extends BodySubscriber {

        @Override
        protected void request0(long n) {
            final Subscription upstream = this.upstream;
            upstream.request(n);
        }

        @Override
        protected void completeOnSubscriptionCancel() {
            completeTrailers(EMPTY_TRAILERS);
        }

        @Override
        public void onComplete() {
            completeTrailers(EMPTY_TRAILERS);
            super.onComplete();
        }

        @Override
        public void onError(Throwable cause) {
            completeTrailers(EMPTY_TRAILERS);
            super.onError(cause);
        }
    }
}
