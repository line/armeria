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

import static java.util.Objects.requireNonNull;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.util.UnstableApi;

import io.netty.util.ReferenceCountUtil;

/**
 * A {@link FixedStreamMessage} that publishes an arbitrary number of objects. It is recommended to use
 * {@link EmptyFixedStreamMessage}, {@link OneElementFixedStreamMessage}, or
 * {@link TwoElementFixedStreamMessage} when publishing less than three objects.
 */
@UnstableApi
public class RegularFixedStreamMessage<T> extends FixedStreamMessage<T> {

    private final T[] objs;

    private int fulfilled;

    private boolean inOnNext;

    /**
     * Creates a new instance with the specified elements.
     */
    protected RegularFixedStreamMessage(T[] objs) {
        requireNonNull(objs, "objs");
        for (int i = 0; i < objs.length; i++) {
            if (objs[i] == null) {
                throw new NullPointerException("objs[" + i + "] is null");
            }
        }

        this.objs = objs.clone();
    }

    @Override
    final void cleanupObjects() {
        while (fulfilled < objs.length) {
            final T obj = objs[fulfilled];
            objs[fulfilled++] = null;
            try {
                onRemoval(obj);
            } finally {
                ReferenceCountUtil.safeRelease(obj);
            }
        }
    }

    @Override
    final void doRequest(SubscriptionImpl subscription, long n) {
        final int oldDemand = requested();
        if (oldDemand >= objs.length) {
            // Already enough demand to finish the stream so don't need to do anything.
            return;
        }
        // As objs.length is fixed, we can safely cap the demand to it here.
        if (n >= objs.length) {
            setRequested(objs.length);
        } else {
            // As objs.length is an int, large demand will always fall into the above branch and there is no
            // chance of overflow, so just simply add the demand.
            setRequested((int) Math.min(oldDemand + n, objs.length));
        }
        if (requested() > oldDemand) {
            doNotify(subscription);
        }
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

        final Subscriber<Object> subscriber = subscription.subscriber();
        for (;;) {
            if (closeEvent() != null) {
                cleanup(subscription);
                return;
            }

            if (fulfilled == objs.length) {
                notifySubscriberOfCloseEvent(subscription, SUCCESSFUL_CLOSE);
                return;
            }

            final int requested = requested();

            if (fulfilled == requested) {
                break;
            }

            while (fulfilled < requested) {
                if (closeEvent() != null) {
                    cleanup(subscription);
                    return;
                }

                T o = objs[fulfilled];
                objs[fulfilled++] = null;
                o = prepareObjectForNotification(subscription, o);
                inOnNext = true;
                try {
                    subscriber.onNext(o);
                } finally {
                    inOnNext = false;
                }
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return false;
    }
}
