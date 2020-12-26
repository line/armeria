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

package com.linecorp.armeria.common;

import javax.annotation.Nullable;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;

final class PublisherBasedHttpResponse extends PublisherBasedStreamMessage<HttpObject> implements HttpResponse {

    PublisherBasedHttpResponse(Publisher<? extends HttpObject> publisher) {
        super(publisher);
    }

    static PublisherBasedHttpResponse from(ResponseHeaders headers,
                                           Publisher<? extends HttpData> contentPublisher) {
        return new PublisherBasedHttpResponse(new HeadersAndContentProcessor(headers, contentPublisher));
    }

    static final class HeadersAndContentProcessor implements Processor<HttpData, HttpObject> {

        private final ResponseHeaders headers;
        @Nullable
        private Subscriber<? super HttpObject> subscriber;
        @Nullable
        private Subscription contentSubscription;

        HeadersAndContentProcessor(ResponseHeaders headers, Publisher<? extends HttpData> contentPublisher) {
            this.headers = headers;
            contentPublisher.subscribe(this);
        }

        @Override
        public void onSubscribe(Subscription contentSubscription) {
            this.contentSubscription = contentSubscription;
        }

        @Override
        public void onNext(HttpData httpData) {
            assert subscriber != null;
            subscriber.onNext(httpData);
        }

        @Override
        public void onError(Throwable t) {
            assert subscriber != null;
            subscriber.onError(t);
        }

        @Override
        public void onComplete() {
            assert subscriber != null;
            subscriber.onComplete();
        }

        @Override
        public void subscribe(Subscriber<? super HttpObject> subscriber) {
            this.subscriber = subscriber;
            subscriber.onSubscribe(new HeadersAndContentSubscription());
        }

        final class HeadersAndContentSubscription implements Subscription {

            private boolean headersSent;

            @Override
            public void request(long n) {
                if (!headersSent) {
                    assert subscriber != null;
                    subscriber.onNext(headers);
                    n--;
                    headersSent = true;
                }
                if (n > 0) {
                    assert contentSubscription != null;
                    contentSubscription.request(n);
                }
            }

            @Override
            public void cancel() {
                assert contentSubscription != null;
                contentSubscription.cancel();
            }
        }
    }
}
