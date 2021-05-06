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

package com.linecorp.armeria.internal.common.resteasy;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.ReferenceCountUtil;

/**
 * An adapter of {@link HttpMessageSubscriber} to {@link Subscriber}.
 */
@SuppressWarnings("ReactiveStreamsSubscriberImplementation")
@UnstableApi
final class HttpMessageSubscriberAdapter implements Subscriber<HttpObject> {

    private static final long MAX_ALLOWED_DATA_LENGTH = Integer.MAX_VALUE;

    private final HttpMessageSubscriber subscriber;
    @Nullable
    private Subscription subscription;
    private long contentLength;

    HttpMessageSubscriberAdapter(HttpMessageSubscriber subscriber) {
        this.subscriber = subscriber;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE); // limit the content length to Long.MAX_VALUE
    }

    @Override
    public void onNext(HttpObject httpObject) {
        final boolean eos = httpObject.isEndOfStream();
        if (httpObject instanceof HttpHeaders) {
            subscriber.onHeaders((HttpHeaders) httpObject);
        } else {
            final HttpData httpData = (HttpData) httpObject;
            final int dataLength = httpData.length();
            if (dataLength > 0) {
                final long allowedDataLength = MAX_ALLOWED_DATA_LENGTH - contentLength;
                if (dataLength > allowedDataLength) {
                    //noinspection ConstantConditions
                    subscription.cancel();
                    onError(new IllegalStateException(
                            "content length greater than " + MAX_ALLOWED_DATA_LENGTH));
                    return;
                }
                contentLength += dataLength;
                // handle data object
                try {
                    subscriber.onData(httpData);
                } finally {
                    ReferenceCountUtil.safeRelease(httpData); // release all Netty allocated resources
                }
            }
        }
        if (eos) {
            subscriber.onComplete();
        }
    }

    @Override
    public void onError(Throwable cause) {
        subscriber.onError(cause);
    }

    @Override
    public void onComplete() {
        subscriber.onComplete();
    }
}
