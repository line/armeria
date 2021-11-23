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

import static com.linecorp.armeria.internal.common.stream.StreamMessageUtil.touchOrCopyAndClose;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link FixedStreamMessage} that publishes an arbitrary number of objects. It is recommended to use
 * {@link EmptyFixedStreamMessage}, {@link OneElementFixedStreamMessage}, or
 * {@link TwoElementFixedStreamMessage} when publishing less than three objects.
 */
@UnstableApi
public class RegularFixedStreamMessage<T> extends AbstractFixedStreamMessage<T> {

    private final T[] objs;

    /**
     * Creates a new instance with the specified elements.
     */
    public RegularFixedStreamMessage(T[] objs) {
        requireNonNull(objs, "objs");
        for (int i = 0; i < objs.length; i++) {
            if (objs[i] == null) {
                throw new NullPointerException("objs[" + i + "] is null");
            }
        }

        this.objs = objs.clone();
    }

    @Override
    T get(int index) {
        return objs[index];
    }

    @Override
    int size() {
        return objs.length;
    }

    @Override
    final List<T> drainAll(boolean withPooledObjects) {
        final int length = objs.length;
        final ImmutableList.Builder<T> builder = ImmutableList.builderWithExpectedSize(length);
        for (T obj : objs) {
            builder.add(touchOrCopyAndClose(obj, withPooledObjects));
        }
        return builder.build();
    }
}
