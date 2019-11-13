/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal;

import com.google.common.annotations.VisibleForTesting;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;

/**
 * Provides a thread-local byte array which may be useful when wanting to avoid allocating a short-living
 * temporary byte array which never escapes the caller's control.
 */
public final class ThreadLocalByteArray {

    @VisibleForTesting
    static final int MAX_CAPACITY = 4096;

    @VisibleForTesting
    static final FastThreadLocal<byte[]> threadLocalByteArray = new FastThreadLocal<>();

    /**
     * Returns a thread-local byte array whose length is equal to or greater than the specified
     * {@code minCapacity}.
     */
    public static byte[] get(int minCapacity) {
        final InternalThreadLocalMap map = InternalThreadLocalMap.get();
        final byte[] array = threadLocalByteArray.get(map);
        if (array != null && array.length >= minCapacity) {
            return array;
        }

        return allocate(map, minCapacity);
    }

    private static byte[] allocate(InternalThreadLocalMap map, int minCapacity) {
        final byte[] newArray = new byte[minCapacity];
        if (minCapacity <= MAX_CAPACITY) {
            threadLocalByteArray.set(map, newArray);
        }
        return newArray;
    }

    private ThreadLocalByteArray() {}
}
