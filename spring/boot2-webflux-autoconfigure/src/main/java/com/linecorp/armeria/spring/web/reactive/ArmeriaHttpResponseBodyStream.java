/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.DefaultSplitHttpResponse;

import io.netty.util.concurrent.EventExecutor;
import reactor.core.publisher.Mono;

final class ArmeriaHttpResponseBodyStream extends DefaultSplitHttpResponse {

    private static final AtomicIntegerFieldUpdater<ArmeriaHttpResponseBodyStream> subscribedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ArmeriaHttpResponseBodyStream.class, "subscribed");
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<ArmeriaHttpResponseBodyStream, Publisher>
            publisherForLateSubscribersUpdater = AtomicReferenceFieldUpdater
            .newUpdater(ArmeriaHttpResponseBodyStream.class, Publisher.class, "publisherForLateSubscribers");

    private volatile int subscribed;

    @Nullable
    private volatile Publisher<HttpData> publisherForLateSubscribers;

    ArmeriaHttpResponseBodyStream(HttpResponse httpResponse, EventExecutor executor) {
        super(httpResponse, executor);
    }

    @Override
    public void subscribe(Subscriber<? super HttpData> s) {
        if (subscribedUpdater.compareAndSet(this, 0, 1)) {
            // The first subscriber.
            super.subscribe(s);
        } else {
            // The other subscribers - notify whether completed successfully only.
            final Publisher<HttpData> publisherForLateSubscribers = this.publisherForLateSubscribers;
            if (publisherForLateSubscribers != null) {
                publisherForLateSubscribers.subscribe(s);
                return;
            }

            @SuppressWarnings({ "unchecked", "rawtypes" })
            final Publisher<HttpData> newPublisher =
                    (Publisher) Mono.fromFuture(whenComplete());
            if (publisherForLateSubscribersUpdater.compareAndSet(this, null, newPublisher)) {
                newPublisher.subscribe(s);
            } else {
                this.publisherForLateSubscribers.subscribe(s);
            }
        }
    }
}
