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

package com.linecorp.armeria.internal;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;

/**
 * A {@link Subscriber} that completes the {@link CompletableFuture} which has taken as the argument in the
 * constructor with {@link HttpHeaders}. The {@link HttpHeaders} contains a status that is not informational.
 * If subscription is finished before subscribing a status, this will complete the future with
 * the {@link HttpHeaders#EMPTY_HEADERS}.
 */
public final class HttpHeaderSubscriber
        implements Subscriber<HttpObject>, BiConsumer<Void, Throwable> {

    private final CompletableFuture<HttpHeaders> future;
    @Nullable
    private Subscription subscription;
    @Nullable
    private HttpHeaders headers;

    /**
     * Create a instance that subscribes until it receives {@link HttpHeaders}.
     */
    public HttpHeaderSubscriber(CompletableFuture<HttpHeaders> future) {
        this.future = future;
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(HttpObject o) {
        if (o instanceof HttpHeaders) {
            final HttpHeaders headers = (HttpHeaders) o;
            final HttpStatus status = headers.status();
            if (status != null && status.codeClass() != HttpStatusClass.INFORMATIONAL && this.headers == null) {
                this.headers = headers;
                assert subscription != null;
                subscription.cancel();
            }
        }
    }

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onComplete() {}

    @Override
    public void accept(Void aVoid, Throwable thrown) {
        if (thrown != null && !(thrown instanceof CancelledSubscriptionException) &&
            !(thrown instanceof AbortedStreamException)) {
            future.completeExceptionally(thrown);
        } else {
            future.complete(firstNonNull(headers, HttpHeaders.EMPTY_HEADERS));
        }
    }
}
