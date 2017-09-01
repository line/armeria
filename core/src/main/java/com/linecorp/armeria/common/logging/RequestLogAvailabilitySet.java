/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Iterators;
import com.google.common.math.IntMath;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * A special {@link Set} implementation that pre-populates all possible combinations of
 * {@link RequestLogAvailability}. Note that the number of all combinations are very small, because most
 * combinations can be merged. e.g. [ REQUEST_START, SCHEME ] and [ SCHEME ] are same because their
 * getterFlags are identical.
 */
final class RequestLogAvailabilitySet extends AbstractSet<RequestLogAvailability> {

    private static final Int2ObjectMap<RequestLogAvailabilitySet> map = new Int2ObjectOpenHashMap<>();

    static {
        // Pre-populate all possible instances.
        final RequestLogAvailability[] values = RequestLogAvailability.values();
        final int end = IntMath.pow(2, values.length);
        for (int i = 0; i < end; i++) {
            int flags = 0;
            for (RequestLogAvailability v : values) {
                if ((i & 1 << v.ordinal()) != 0) {
                    flags |= v.setterFlags();
                }
            }

            if (map.containsKey(flags)) {
                continue;
            }

            map.put(flags, new RequestLogAvailabilitySet(flags));
        }
    }

    static RequestLogAvailabilitySet of(int flags) {
        final RequestLogAvailabilitySet availabilities = map.get(flags);
        assert availabilities != null;
        return availabilities;
    }

    private final int flags;
    private final RequestLogAvailability[] values;

    private RequestLogAvailabilitySet(int flags) {
        this.flags = flags;

        final List<RequestLogAvailability> values = new ArrayList<>();
        for (RequestLogAvailability v : RequestLogAvailability.values()) {
            if ((flags & v.getterFlags()) == v.getterFlags()) {
                values.add(v);
            }
        }
        this.values = values.toArray(new RequestLogAvailability[values.size()]);
    }

    @Override
    public Iterator<RequestLogAvailability> iterator() {
        return Iterators.forArray(values);
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public boolean contains(Object e) {
        if (!(e instanceof RequestLogAvailability)) {
            return false;
        }

        final int flags = ((RequestLogAvailability) e).getterFlags();
        return (this.flags & flags) == flags;
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
