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

import static com.linecorp.armeria.common.stream.SubscriptionOption.NOTIFY_CANCELLATION;
import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static java.util.Objects.requireNonNull;

import org.reactivestreams.Subscriber;

final class StreamMessageUtil {

    static Throwable abortedOrLate(Subscriber<?> oldSubscriber) {
        if (oldSubscriber instanceof AbortingSubscriber) {
            return AbortedStreamException.get();
        }

        return new IllegalStateException("subscribed by other subscriber already");
    }

    static boolean containsWithPooledObjects(SubscriptionOption... options) {
        requireNonNull(options, "options");
        for (SubscriptionOption option : options) {
            if (option == WITH_POOLED_OBJECTS) {
                return true;
            }
        }

        return false;
    }

    static boolean containsNotifyCancellation(SubscriptionOption... options) {
        requireNonNull(options, "options");
        for (SubscriptionOption option : options) {
            if (option == NOTIFY_CANCELLATION) {
                return true;
            }
        }

        return false;
    }

    private StreamMessageUtil() {}
}
