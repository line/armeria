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

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link FixedStreamMessage} that publishes three objects.
 */
@UnstableApi
public class ThreeElementFixedStreamMessage<T> extends FixedStreamMessage<T> {

    @Nullable
    private T obj1;
    @Nullable
    private T obj2;
    @Nullable
    private T obj3;

    private boolean inOnNext;
    private volatile int demand;

    /**
     * Constructs a new {@link ThreeElementFixedStreamMessage} for the given objects.
     */
    protected ThreeElementFixedStreamMessage(T obj1, T obj2, T obj3) {
        this.obj1 = obj1;
        this.obj2 = obj2;
        this.obj3 = obj3;
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
        if (obj3 != null) {
            try {
                onRemoval(obj3);
            } finally {
                StreamMessageUtil.closeOrAbort(obj3, cause);
            }
            obj3 = null;
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
        if (obj3 == null) {
            return;
        }

        if (n <= 0) {
            onError(new IllegalArgumentException(
                    "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
            return;
        }

        if (inOnNext) {
            // re-entrance
            if (n == 1) {
                demand++;
            } else {
                demand += 2;
            }
            return;
        }

        if (n >= 3) {
            inOnNext = true;
            try {
                if (obj1 != null) {
                    final T item = obj1;
                    obj1 = null;
                    onNext(item);
                }
                if (obj2 != null) {
                    final T item = obj2;
                    obj2 = null;
                    onNext(item);
                }
                if (obj3 != null) {
                    final T item = obj3;
                    obj3 = null;
                    onNext(item);
                }
            } finally {
                inOnNext = false;
            }
            onComplete();
        } else {
            demand += n;
            if (obj1 != null) {
                final T item = obj1;
                obj1 = null;
                inOnNext = true;
                try {
                    onNext(item);
                } finally {
                    inOnNext = false;
                }
                demand--;
            }

            if (obj2 != null && demand > 0) {
                final T item = obj2;
                obj2 = null;
                try {
                    onNext(item);
                } finally {
                    inOnNext = false;
                }
                demand--;
            }

            if (obj3 != null && demand > 0) {
                final T item = obj3;
                obj3 = null;
                onNext(item);
                onComplete();
            }
        }
    }

    @Override
    public void cancel() {
        if (obj3 == null) {
            return;
        }
        super.cancel();
    }

    @Override
    public void abort() {
        if (obj3 == null) {
            return;
        }
        super.abort();
    }

    @Override
    public void abort(Throwable cause) {
        if (obj3 == null) {
            return;
        }
        super.abort(cause);
    }
}
