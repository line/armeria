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

import static java.util.Objects.requireNonNull;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

// TODO(ikhoon): Remove this and allow to use pooled objects
abstract class SimpleStreamMessage<T> implements StreamMessage<T> {

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor) {
        subscribe(subscriber, false);
    }

    @Override
    public final void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                                SubscriptionOption... options) {
        subscribe(subscriber, containsNotifyCancellation(options));
    }

    abstract void subscribe(Subscriber<? super T> subscriber, boolean notifyCancellation);

    private static boolean containsNotifyCancellation(SubscriptionOption... options) {
        requireNonNull(options, "options");
        for (SubscriptionOption option : options) {
            if (option == SubscriptionOption.NOTIFY_CANCELLATION) {
                return true;
            }
        }

        return false;
    }
}
