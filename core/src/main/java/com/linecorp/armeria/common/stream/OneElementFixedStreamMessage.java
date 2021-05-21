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

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;

import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;

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
    final void cleanupObjects(@Nullable Throwable cause) {
        if (obj != null) {
            try {
                onRemoval(obj);
            } finally {
                StreamMessageUtil.closeOrAbort(obj, cause);
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

        try {
            subscription.subscriber().onNext(published);
        } catch (Throwable t) {
            // Just abort this stream so subscriber().onError(e) is called and resources are cleaned up.
            abort(t);
            throwIfFatal(t);
            logger.warn("Subscriber.onNext({}) should not raise an exception. subscriber: {}",
                        published, subscription.subscriber(), t);
            return;
        }
        notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
    }

    @Override
    public boolean hasNext() {
        return obj != null;
    }

    @Override
    public T next() {
        if (obj == null) {
            throw new NoSuchElementException();
        } else {
            final T next = obj;
            obj = null;
            return next;
        }
    }
}
