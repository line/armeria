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

package com.linecorp.armeria.common.stream;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NeverInvokedSubscriber<T> implements Subscriber<T> {

    private static final Logger logger = LoggerFactory.getLogger(NeverInvokedSubscriber.class);

    private static final NeverInvokedSubscriber<Object> INSTANCE = new NeverInvokedSubscriber<>();

    @SuppressWarnings("unchecked")
    static <T> NeverInvokedSubscriber<T> get() {
        return (NeverInvokedSubscriber<T>) INSTANCE;
    }

    @Override
    public void onSubscribe(Subscription s) {
        s.cancel();
        logger.warn("onSubscribe({}) is invoked.", s);
    }

    @Override
    public void onNext(T t) {
        logger.warn("onNext({}) is invoked.", t);
    }

    @Override
    public void onError(Throwable t) {
        logger.warn("onError() is invoked with:", t);
    }

    @Override
    public void onComplete() {
        logger.warn("onComplete() is invoked.");
    }
}
