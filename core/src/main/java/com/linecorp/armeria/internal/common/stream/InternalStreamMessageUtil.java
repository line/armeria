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

package com.linecorp.armeria.internal.common.stream;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.stream.SubscriptionOption;

public final class InternalStreamMessageUtil {

    public static final int DEFAULT_FILE_BUFFER_SIZE = 8192;
    public static final SubscriptionOption[] EMPTY_OPTIONS = {};
    public static final SubscriptionOption[] POOLED_OBJECTS = { SubscriptionOption.WITH_POOLED_OBJECTS };
    public static final SubscriptionOption[] CANCELLATION_OPTION = { SubscriptionOption.NOTIFY_CANCELLATION };
    public static final SubscriptionOption[] CANCELLATION_AND_POOLED_OPTIONS =
            { SubscriptionOption.WITH_POOLED_OBJECTS, SubscriptionOption.NOTIFY_CANCELLATION };

    public static boolean containsWithPooledObjects(SubscriptionOption... options) {
        requireNonNull(options, "options");
        for (SubscriptionOption option : options) {
            if (option == SubscriptionOption.WITH_POOLED_OBJECTS) {
                return true;
            }
        }

        return false;
    }

    public static boolean containsNotifyCancellation(SubscriptionOption... options) {
        requireNonNull(options, "options");
        for (SubscriptionOption option : options) {
            if (option == SubscriptionOption.NOTIFY_CANCELLATION) {
                return true;
            }
        }

        return false;
    }

    public static SubscriptionOption[] toSubscriptionOptions(boolean withPooledObjects,
                                                             boolean notifyCancellation) {
        if (withPooledObjects) {
            if (notifyCancellation) {
                return CANCELLATION_AND_POOLED_OPTIONS;
            } else {
                return POOLED_OBJECTS;
            }
        } else {
            if (notifyCancellation) {
                return CANCELLATION_OPTION;
            } else {
                return EMPTY_OPTIONS;
            }
        }
    }

    private InternalStreamMessageUtil() {}
}
