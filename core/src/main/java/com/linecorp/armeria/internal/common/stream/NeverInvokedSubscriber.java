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

package com.linecorp.armeria.internal.common.stream;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public final class NeverInvokedSubscriber<T> implements Subscriber<T> {

    private static final NeverInvokedSubscriber<Object> INSTANCE = new NeverInvokedSubscriber<>();

    @SuppressWarnings("unchecked")
    public static <T> NeverInvokedSubscriber<T> get() {
        return (NeverInvokedSubscriber<T>) INSTANCE;
    }

    @Override
    public void onSubscribe(Subscription s) {
        throw new IllegalStateException("onSubscribe(" + s + ')');
    }

    @Override
    public void onNext(T t) {
        throw new IllegalStateException("onNext(" + t + ')');
    }

    @Override
    public void onError(Throwable t) {
        throw new IllegalStateException("onError(" + t + ')', t);
    }

    @Override
    public void onComplete() {
        throw new IllegalStateException("onComplete()");
    }
}
