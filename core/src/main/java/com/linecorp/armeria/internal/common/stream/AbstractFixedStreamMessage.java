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

import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link FixedStreamMessage} that publishes an arbitrary number of objects. It is recommended to use
 * {@link EmptyFixedStreamMessage}, {@link OneElementFixedStreamMessage}, or
 * {@link TwoElementFixedStreamMessage} when publishing less than three objects.
 */
abstract class AbstractFixedStreamMessage<T> extends FixedStreamMessage<T> {

    private int fulfilled;
    private boolean inOnNext;
    private boolean cancelled;

    private volatile int demand;

    @Override
    public final long demand() {
        return demand;
    }

    abstract T get(int index);

    abstract int size();

    @Override
    final void cleanupObjects(@Nullable Throwable cause) {
        final int size = size();
        while (fulfilled < size) {
            final T obj = get(fulfilled++);
            StreamMessageUtil.closeOrAbort(obj, cause);
        }
    }

    @Override
    final List<T> drainAll(boolean withPooledObjects) {
        final List<T> all = drainAll0(withPooledObjects);
        fulfilled = size();
        return all;
    }

    abstract List<T> drainAll0(boolean withPooledObjects);

    @Override
    public final void request(long n) {
        final EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            request0(n);
        } else {
            executor.execute(() -> request0(n));
        }
    }

    private void request0(long n) {
        if (isDone()) {
            // The subscription has been closed. An additional request should be ignored.
            // https://github.com/reactive-streams/reactive-streams-jvm#3.6
            return;
        }

        if (n <= 0) {
            onError(new IllegalArgumentException(
                    "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
            return;
        }

        final int size = size();
        if (fulfilled == size) {
            return;
        }

        final int oldDemand = demand;
        if (oldDemand >= size) {
            // Already enough demand to finish the stream so don't need to do anything.
            return;
        }

        // As size is fixed, we can safely cap the demand to it here.
        final int remaining = size - fulfilled;
        if (n >= remaining) {
            demand = remaining;
        } else {
            // As objs.length is an int, large demand will always fall into the above branch and there is no
            // chance of overflow, so just simply add the demand.
            demand = (int) Math.min(oldDemand + n, remaining);
        }

        if (inOnNext) {
            return;
        }

        for (;;) {
            if (cancelled) {
                return;
            }

            while (demand > 0 && fulfilled < size) {
                if (cancelled) {
                    return;
                }

                final T o = get(fulfilled++);
                inOnNext = true;
                demand--;
                try {
                    onNext(o);
                } finally {
                    inOnNext = false;
                }
            }

            if (fulfilled == size) {
                onComplete();
                return;
            }

            if (demand == 0) {
                return;
            }
        }
    }

    @Override
    public final void cancel() {
        if (isDone()) {
            return;
        }
        cancelled = true;
        super.cancel();
    }

    @Override
    public void abort() {
        if (isDone()) {
            return;
        }
        cancelled = true;
        super.abort();
    }

    @Override
    public void abort(Throwable cause) {
        if (isDone()) {
            return;
        }
        cancelled = true;
        super.abort(cause);
    }

    private boolean isDone() {
        return cancelled || isComplete();
    }
}
