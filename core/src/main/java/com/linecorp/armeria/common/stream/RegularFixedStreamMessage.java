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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.concurrent.EventExecutor;

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
    private boolean cancelled;

    private volatile int demand;

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
    public final boolean isEmpty() {
        return false;
    }

    @Override
    public long demand() {
        return demand;
    }

    @Override
    final void cleanupObjects(@Nullable Throwable cause) {
        while (fulfilled < objs.length) {
            final T obj = objs[fulfilled];
            objs[fulfilled++] = null;
            StreamMessageUtil.closeOrAbort(obj, cause);
        }
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
        if (cancelled) {
            // The subscription has been closed. An additional request should be ignored.
            // https://github.com/reactive-streams/reactive-streams-jvm#3.6
            return;
        }

        if (n <= 0) {
            onError(new IllegalArgumentException(
                    "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
            return;
        }

        if (fulfilled == objs.length) {
            return;
        }

        final int oldDemand = demand;
        if (oldDemand >= objs.length) {
            // Already enough demand to finish the stream so don't need to do anything.
            return;
        }

        // As objs.length is fixed, we can safely cap the demand to it here.
        if (n >= objs.length) {
            demand = objs.length;
        } else {
            // As objs.length is an int, large demand will always fall into the above branch and there is no
            // chance of overflow, so just simply add the demand.
            demand = (int) Math.min(oldDemand + n, objs.length);
        }

        if (inOnNext) {
            return;
        }

        for (;;) {
            if (cancelled) {
                return;
            }

            while (demand > 0 && fulfilled < objs.length) {
                if (cancelled) {
                    return;
                }

                final T o = objs[fulfilled];
                objs[fulfilled++] = null;
                inOnNext = true;
                demand--;
                try {
                    onNext(o);
                } finally {
                    inOnNext = false;
                }
            }

            if (fulfilled == objs.length) {
                onComplete();
                return;
            }

            if (demand == 0) {
                return;
            }
        }
    }

    @Override
    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        super.cancel();
    }

    @Override
    public void abort() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        super.abort();
    }

    @Override
    public void abort(Throwable cause) {
        if (cancelled) {
            return;
        }
        cancelled = true;
        super.abort(cause);
    }
}
