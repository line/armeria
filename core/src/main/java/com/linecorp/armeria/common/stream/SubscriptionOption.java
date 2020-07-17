/*
 * Copyright 2019 LINE Corporation
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

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.concurrent.EventExecutor;

/**
 * Options used when subscribing to a {@link StreamMessage}.
 *
 * @see StreamMessage#subscribe(Subscriber, SubscriptionOption...)
 * @see StreamMessage#subscribe(Subscriber, EventExecutor, SubscriptionOption...)
 */
public enum SubscriptionOption {

    /**
     * To get notified by {@link Subscriber#onError(Throwable)} even when the {@link StreamMessage} is
     * {@linkplain Subscription#cancel() cancelled}.
     */
    NOTIFY_CANCELLATION,

    /**
     * (Advanced users only) To receive the pooled {@link HttpData} as is, without making a copy.
     * If you don't know what this means, do not specify this when you subscribe the {@link StreamMessage}.
     *
     * @see PooledObjects
     */
    @UnstableApi
    WITH_POOLED_OBJECTS
}
