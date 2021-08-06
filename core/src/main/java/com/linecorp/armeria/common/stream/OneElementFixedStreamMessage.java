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

import static com.linecorp.armeria.common.stream.StreamMessageUtil.touchOrCopyAndClose;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.concurrent.EventExecutor;

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
    public long demand() {
        return 0;
    }

    @Override
    final void cleanupObjects(@Nullable Throwable cause) {
        if (obj != null) {
            StreamMessageUtil.closeOrAbort(obj, cause);
            obj = null;
        }
    }

    @Override
    final List<T> drainAll(boolean withPooledObjects) {
        assert obj != null;
        final T item = touchOrCopyAndClose(obj, withPooledObjects);
        obj = null;
        return ImmutableList.of(item);
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
        if (obj == null) {
            return;
        }

        if (n <= 0) {
            onError(new IllegalArgumentException(
                    "n: " + n + " (expected: > 0, see Reactive Streams specification rule 3.9)"));
            return;
        }

        final T item = obj;
        obj = null;
        onNext(item);
        onComplete();
    }

    @Override
    public void cancel() {
        if (obj == null) {
            return;
        }
        super.cancel();
    }

    @Override
    public void abort() {
        if (obj == null) {
            return;
        }
        super.abort();
    }

    @Override
    public void abort(Throwable cause) {
        if (obj == null) {
            return;
        }
        super.abort(cause);
    }
}
