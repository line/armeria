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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.UnstableApi;

import io.netty.util.ReferenceCountUtil;

/**
 * A {@link FixedStreamMessage} that only publishes one object.
 */
@UnstableApi
public class OneElementFixedStreamMessage<T> extends FixedStreamMessage<T> {

    @Nullable
    private T obj;

    protected OneElementFixedStreamMessage(T obj) {
        this.obj = obj;
    }

    @Override
    final void cleanupObjects() {
        if (obj != null) {
            try {
                onRemoval(obj);
            } finally {
                ReferenceCountUtil.safeRelease(obj);
            }
            obj = null;
        }
    }

    @Override
    final void doRequest(SubscriptionImpl subscription, long unused) {
        if (requested() != 0) {
            // Already have demand, so don't need to do anything, the current demand will complete the
            // stream.
            return;
        }
        setRequested(1);
        doNotify(subscription);
    }

    @Override
    public final boolean isEmpty() {
        return false;
    }

    private void doNotify(SubscriptionImpl subscription) {
        // Only called with correct demand, so no need to even check it.
        assert obj != null;
        final T published = prepareObjectForNotification(subscription, obj);
        obj = null;
        // Not possible to have re-entrant onNext with only one item, so no need to keep track of it.
        subscription.subscriber().onNext(published);
        notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
    }
}
