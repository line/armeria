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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.unsafe.PooledObjects;

/**
 * A {@link FixedStreamMessage} that aggregates an arbitrary number of objects.
 * The aggregated objects can be published after this stream is closed.
 */
public class AggregatingStreamMessage<T> extends AbstractFixedStreamMessage<T> implements StreamWriter<T> {

    private final List<T> objs;
    private boolean closed;

    public AggregatingStreamMessage(int initialCapacity) {
        objs = new ArrayList<>(initialCapacity);
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean tryWrite(T o) {
        return objs.add(o);
    }

    @Override
    public CompletableFuture<Void> whenConsumed() {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void close(Throwable cause) {
        requireNonNull(cause, "cause");
        closed = true;
        abort(cause);
    }

    private void ensureClosed() {
        checkState(closed, "stream is not closed yet.");
    }

    @Override
    T get(int index) {
        ensureClosed();
        return objs.get(index);
    }

    @Override
    int size() {
        ensureClosed();
        return objs.size();
    }

    @Override
    List<T> drainAll(boolean withPooledObjects) {
        ensureClosed();
        if (withPooledObjects) {
            for (T obj : objs) {
                PooledObjects.touch(obj);
            }
            return Collections.unmodifiableList(objs);
        } else {
            final ImmutableList.Builder<T> builder = ImmutableList.builderWithExpectedSize(objs.size());
            for (T obj : objs) {
                builder.add(PooledObjects.copyAndClose(obj));
            }
            return builder.build();
        }
    }
}
