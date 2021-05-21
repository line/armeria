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
 * A {@link FixedStreamMessage} that publishes two objects.
 */
@UnstableApi
public class TwoElementFixedStreamMessage<T> extends FixedStreamMessage<T> {

    @Nullable
    private T obj1;
    @Nullable
    private T obj2;

    private boolean inOnNext;

    /**
     * Constructs a new {@link TwoElementFixedStreamMessage} for the given objects.
     */
    protected TwoElementFixedStreamMessage(T obj1, T obj2) {
        this.obj1 = obj1;
        this.obj2 = obj2;
    }

    @Override
    final void cleanupObjects(@Nullable Throwable cause) {
        if (obj1 != null) {
            try {
                onRemoval(obj1);
            } finally {
                StreamMessageUtil.closeOrAbort(obj1, cause);
            }
            obj1 = null;
        }
        if (obj2 != null) {
            try {
                onRemoval(obj2);
            } finally {
                StreamMessageUtil.closeOrAbort(obj2, cause);
            }
            obj2 = null;
        }
    }

    @Override
    final void doRequest(SubscriptionImpl subscription, long n) {
        final int oldDemand = requested();
        if (oldDemand >= 2) {
            // Already have demand, so don't need to do anything, the current demand will complete the
            // stream.
            return;
        }
        setRequested(n >= 2 ? oldDemand + 2 : oldDemand + 1);
        doNotify(subscription);
    }

    @Override
    public final boolean isEmpty() {
        return false;
    }

    private void doNotify(SubscriptionImpl subscription) {
        if (inOnNext) {
            // Do not let Subscriber.onNext() reenter, because it can lead to weird-looking event ordering
            // for a Subscriber implemented like the following:
            //
            //   public void onNext(Object e) {
            //       subscription.request(1);
            //       ... Handle 'e' ...
            //   }
            //
            // Note that we do not call this method again, because we are already in the notification loop
            // and it will consume the element we've just added in addObjectOrEvent() from the queue as
            // expected.
            //
            // We do not need to worry about synchronizing the access to 'inOnNext' because the subscriber
            // methods must be on the same thread, or synchronized, according to Reactive Streams spec.
            return;
        }

        // Demand is always positive, so no need to check it.
        if (obj1 != null) {
            final T obj1 = this.obj1;
            this.obj1 = null;
            doNotifyObject(subscription, obj1);
        }

        if (requested() >= 2 && obj2 != null) {
            final T obj2 = this.obj2;
            this.obj2 = null;
            doNotifyObject(subscription, obj2);
            notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
        }
    }

    private void doNotifyObject(SubscriptionImpl subscription, T obj) {
        final T published = prepareObjectForNotification(subscription, obj);
        inOnNext = true;
        try {
            subscription.subscriber().onNext(published);
        } catch (Throwable t) {
            // Just abort this stream so subscriber().onError(e) is called and resources are cleaned up.
            abort(t);
            throwIfFatal(t);
            logger.warn("Subscriber.onNext({}) should not raise an exception. subscriber: {}",
                        obj, subscription.subscriber(), t);
        } finally {
            inOnNext = false;
        }
    }

    @Override
    public boolean hasNext() {
        return obj2 != null;
    }

    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        final T o;
        if (obj1 != null) {
            o = obj1;
            obj1 = null;
        } else {
            o = obj2;
            obj2 = null;
        }
        return o;
    }
}
