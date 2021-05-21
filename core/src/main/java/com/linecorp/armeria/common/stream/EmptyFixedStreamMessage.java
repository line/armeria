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

import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link FixedStreamMessage} that publishes no objects, just a close event.
 */
@UnstableApi
public class EmptyFixedStreamMessage<T> extends FixedStreamMessage<T> {

    // No objects, so just notify of close as soon as there is demand.
    @Override
    final void doRequest(SubscriptionImpl subscription, long unused) {
        if (requested() != 0) {
            // Already have demand so don't need to do anything.
            return;
        }
        setRequested(1);
        notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
    }

    @Override
    public final boolean isEmpty() {
        return true;
    }

    @Override
    final void cleanupObjects(@Nullable Throwable cause) {
        // Empty streams have no objects to clean.
    }

    @Override
    public boolean hasNext() {
        return false;
    }

    @Override
    public T next() {
        throw new NoSuchElementException();
    }
}
