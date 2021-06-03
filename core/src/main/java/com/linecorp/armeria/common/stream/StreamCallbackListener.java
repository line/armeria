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

package com.linecorp.armeria.common.stream;

import org.reactivestreams.Subscriber;

import io.netty.util.concurrent.EventExecutor;

/**
 * TBU.
 */
public interface StreamCallbackListener<T> {

    /**
     * Invoked after an element is removed from the {@link StreamMessage} and before
     * {@link Subscriber#onNext(Object)} is invoked.
     *
     * @param t the removed element
     */
    default void onRemoval(T t) {}

    /**
     * Invoked whenever a new demand is requested.
     */
    default void onRequest(long n) {}

    /**
     * Invoked when a subscriber subscribes.
     */
    default void onSubscribe(EventExecutor executor, SubscriptionOption[] options) {}
}
