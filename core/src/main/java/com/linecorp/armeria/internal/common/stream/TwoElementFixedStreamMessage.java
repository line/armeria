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

package com.linecorp.armeria.internal.common.stream;

import static com.linecorp.armeria.internal.common.stream.StreamMessageUtil.touchOrCopyAndClose;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.concurrent.EventExecutor;

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
    private boolean requested;

    /**
     * Constructs a new {@link TwoElementFixedStreamMessage} for the given objects.
     */
    public TwoElementFixedStreamMessage(T obj1, T obj2) {
        this.obj1 = obj1;
        this.obj2 = obj2;
    }

    @Override
    public long demand() {
        // Since the objects is drained as soon as it is requested, The demand will be zero in most cases.
        // But the demand could be one if a subscriber calls `subscription.request(n)` while receiving a object
        // via 'onNext(t)'
        return requested ? 1 : 0;
    }

    @Override
    final void cleanupObjects(@Nullable Throwable cause) {
        if (obj1 != null) {
            StreamMessageUtil.closeOrAbort(obj1, cause);
            obj1 = null;
        }
        if (obj2 != null) {
            StreamMessageUtil.closeOrAbort(obj2, cause);
            obj2 = null;
        }
    }

    @Override
    final List<T> drainAll(boolean withPooledObjects) {
        assert obj1 != null;
        final List<T> objs = ImmutableList.of(touchOrCopyAndClose(obj1, withPooledObjects),
                                              touchOrCopyAndClose(obj2, withPooledObjects));
        obj1 = obj2 = null;
        return objs;
    }

    @Override
    public void request(long n) {
        final EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            request0(n);
        } else {
            executor.execute(() -> request0(n));
        }
    }

    private void request0(long n) {
        if (obj2 == null) {
            return;
        }

        if (n <= 0) {
            onError(new IllegalArgumentException(
                    "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
            return;
        }

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
            requested = true;
            return;
        }

        if (n >= 2) {
            // All elements will be consumed. No need to restore inOnNext
            inOnNext = true;
            if (obj1 != null) {
                final T item = obj1;
                obj1 = null;
                onNext(item);
            }

            if (obj2 != null) {
                final T item = obj2;
                obj2 = null;
                onNext(item);
                onComplete();
            }
        } else {
            if (obj1 != null) {
                final T item = obj1;
                obj1 = null;
                inOnNext = true;
                onNext(item);
                inOnNext = false;
                n--;
            }

            if ((n > 0 || requested) && obj2 != null) {
                final T item = obj2;
                obj2 = null;
                onNext(item);
                onComplete();
            }
        }
    }

    @Override
    public void cancel() {
        if (obj2 == null) {
            return;
        }
        super.cancel();
    }

    @Override
    public void abort() {
        if (obj2 == null) {
            return;
        }
        super.abort();
    }

    @Override
    public void abort(Throwable cause) {
        if (obj2 == null) {
            return;
        }
        super.abort(cause);
    }
}
