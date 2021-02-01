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

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.unsafe.PooledObjects;

final class StreamMessageUtil {

    public static final SubscriptionOption[] EMPTY_OPTIONS = {};

    static boolean containsWithPooledObjects(SubscriptionOption... options) {
        requireNonNull(options, "options");
        for (SubscriptionOption option : options) {
            if (option == SubscriptionOption.WITH_POOLED_OBJECTS) {
                return true;
            }
        }

        return false;
    }

    static boolean containsNotifyCancellation(SubscriptionOption... options) {
        requireNonNull(options, "options");
        for (SubscriptionOption option : options) {
            if (option == SubscriptionOption.NOTIFY_CANCELLATION) {
                return true;
            }
        }

        return false;
    }

    static void closeOrAbort(Object obj, @Nullable Throwable cause) {
        if (obj instanceof StreamMessage) {
            final StreamMessage<?> streamMessage = (StreamMessage<?>) obj;
            if (cause == null) {
                streamMessage.abort();
            } else {
                streamMessage.abort(cause);
            }
            return;
        }

        if (obj instanceof Publisher) {
            ((Publisher<?>) obj).subscribe(AbortingSubscriber.get(cause));
            return;
        }

        PooledObjects.close(obj);
    }

    static void closeOrAbort(Object obj) {
        closeOrAbort(obj, null);
    }

    private StreamMessageUtil() {}
}
