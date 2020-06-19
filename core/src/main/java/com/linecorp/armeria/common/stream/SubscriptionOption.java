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

import com.linecorp.armeria.internal.stream.InternalSubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

/**
 * Options used when subscribing to a {@link StreamMessage}. This class is sealed to Armeria and can only be
 * implemented here.
 *
 * @see StreamMessage#subscribe(Subscriber, SubscriptionOption...)
 * @see StreamMessage#subscribe(Subscriber, EventExecutor, SubscriptionOption...)
 */
public interface SubscriptionOption {

    /**
     * To get notified by {@link Subscriber#onError(Throwable)} even when the {@link StreamMessage} is
     * {@linkplain Subscription#cancel() cancelled}.
     */
    SubscriptionOption NOTIFY_CANCELLATION = new InternalSubscriptionOption();
}
