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
 * under the License.
 */

package com.linecorp.armeria.internal.common.stream;

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.EventExecutor;

public final class SubscriberUtil {

    private static final Logger logger = LoggerFactory.getLogger(SubscriberUtil.class);

    public static void failLateSubscriber(EventExecutor executor,
                                          Subscriber<?> lateSubscriber, Subscriber<?> oldSubscriber) {
        final Throwable cause = abortedOrLate(oldSubscriber);
        if (executor.inEventLoop()) {
            failLateSubscriber0(lateSubscriber, cause);
        } else {
            executor.execute(() -> {
                failLateSubscriber0(lateSubscriber, cause);
            });
        }
    }

    private static void failLateSubscriber0(Subscriber<?> lateSubscriber, Throwable cause) {
        try {
            lateSubscriber.onSubscribe(NoopSubscription.get());
            lateSubscriber.onError(cause);
        } catch (Throwable t) {
            throwIfFatal(t);
            logger.warn("Subscriber should not throw an exception. subscriber: {}", lateSubscriber, t);
        }
    }

    public static Throwable abortedOrLate(Subscriber<?> oldSubscriber) {
        if (oldSubscriber instanceof AbortingSubscriber) {
            return ((AbortingSubscriber<?>) oldSubscriber).cause();
        }

        return new IllegalStateException("subscribed by other subscriber already");
    }

    private SubscriberUtil() {}
}
