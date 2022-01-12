/*
 * Copyright 2022 LINE Corporation
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
 * under the Licenses
 */

package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

final class ContentAwareMultipartCollector<T> implements Subscriber<BodyPart> {
    private static final Logger logger = LoggerFactory.getLogger(ContentAwareMultipartCollector.class);
    private final List<T> result = new ArrayList<>();
    private final CompletableFuture<List<T>> future = new CompletableFuture<>();
    @Nullable
    private Subscription subscription;
    private int inProgressCount;
    private boolean canComplete;

    private final Function<? super BodyPart, CompletableFuture<? extends T>> function;
    private final EventExecutor eventExecutor;

    ContentAwareMultipartCollector(StreamMessage<? extends BodyPart> publisher,
                                   Function<? super BodyPart, CompletableFuture<? extends T>> function,
                                   EventExecutor eventExecutor,
                                   SubscriptionOption... options) {
        this.function = function;
        this.eventExecutor = eventExecutor;
        publisher.subscribe(this, eventExecutor, options);
    }

    public CompletableFuture<List<T>> future() {
        return future;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        this.subscription = subscription;
        this.subscription.request(1);
    }

    @Override
    public void onNext(BodyPart bodyPart) {
        assert subscription != null;
        inProgressCount++;
        try {
            function.apply(bodyPart).handleAsync((t, throwable) -> {
                inProgressCount--;
                if (throwable != null) {
                    subscription.cancel();
                    future.completeExceptionally(throwable);
                    return null;
                }
                result.add(t);
                if (canComplete) {
                    doComplete();
                } else {
                    subscription.request(1);
                }
                return null;
            }, eventExecutor);
        } catch (Throwable throwable) {
            inProgressCount--;
            subscription.cancel();
            future.completeExceptionally(throwable);
        }
    }

    @Override
    public void onError(Throwable t) {
        future.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        canComplete = true;
        doComplete();
    }

    @SuppressWarnings("UnstableApiUsage")
    private void doComplete() {
        // The last BodyPart is in-progress.
        if (inProgressCount != 0) {
            return;
        }
        if (future.isDone()) {
            return;
        }
        future.complete(result);
    }
}
