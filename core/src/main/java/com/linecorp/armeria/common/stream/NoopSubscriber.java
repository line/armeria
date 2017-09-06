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

package com.linecorp.armeria.common.stream;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A {@link Subscriber} that discards all elements silently.
 */
public final class NoopSubscriber<T> implements Subscriber<T> {

    private static final NoopSubscriber<?> INSTANCE = new NoopSubscriber<>();

    /**
     * Returns a singleton {@link NoopSubscriber}.
     */
    @SuppressWarnings("unchecked")
    public static <T> NoopSubscriber<T> get() {
        return (NoopSubscriber<T>) INSTANCE;
    }

    @Override
    public void onSubscribe(Subscription s) {
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(T t) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onComplete() {}
}
