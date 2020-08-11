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

package com.linecorp.armeria.common.multipart;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;

final class ListenableStreamMessage<T> extends DefaultStreamMessage<T> {

    @SuppressWarnings("rawtypes")
    private static final AtomicLongFieldUpdater<ListenableStreamMessage> demandUpdater =
            AtomicLongFieldUpdater.newUpdater(ListenableStreamMessage.class, "demand");

    @Nullable
    private final LongConsumer onRequest;
    @Nullable
    private final Runnable onCancel;
    @Nullable
    private final Consumer<T> onEmit;

    private volatile long demand;
    private volatile boolean isSubscribed;

    ListenableStreamMessage(@Nullable LongConsumer onRequest,
                            @Nullable Runnable onCancel,
                            @Nullable Consumer<T> onEmit) {
        this.onRequest = onRequest;
        this.onCancel = onCancel;
        this.onEmit = onEmit;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        isSubscribed = true;
        final Subscriber<T> wrapped = new SubscriberWrapper<T>(subscriber) {
            @Override
            public void onSubscribe(Subscription s) {
                super.onSubscribe(new SubscriptionWrapper(s) {
                    @Override
                    public void request(long n) {
                        if (n == Long.MAX_VALUE) {
                            demand = Long.MAX_VALUE;
                        } else {
                            demandUpdater.getAndAdd(ListenableStreamMessage.this, n);
                        }

                        if (onRequest != null) {
                            onRequest.accept(demand);
                        }
                        super.request(n);
                    }

                    @Override
                    public void cancel() {
                        if (onCancel != null) {
                            onCancel.run();
                        }
                        abort(CancelledSubscriptionException.get());
                        super.cancel();
                    }
                });
            }

            @Override
            public void onNext(T t) {
                if (demand != Long.MAX_VALUE) {
                    demandUpdater.decrementAndGet(ListenableStreamMessage.this);
                }
                super.onNext(t);
                if (onEmit != null) {
                    onEmit.accept(t);
                }
            }
        };
        super.subscribe(wrapped);
    }

    boolean isSubscribed() {
        return isSubscribed;
    }

    long demand() {
        return demand;
    }
}
