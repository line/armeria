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

package com.linecorp.armeria.common.logback;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Sets;

final class UnionMap<K, V> extends AbstractMap<K, V> {

    private final Map<K, V> first;
    private final Map<K, V> second;
    private int size = -1;
    @Nullable
    private Set<Entry<K, V>> entrySet;

    UnionMap(Map<K, V> first, Map<K, V> second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public int size() {
        if (size >= 0) {
            return size;
        }

        final Map<K, V> a;
        final Map<K, V> b;
        if (first.size() >= second.size()) {
            a = first;
            b = second;
        } else {
            a = second;
            b = first;
        }

        int size = a.size();
        if (!b.isEmpty()) {
            for (K k : b.keySet()) {
                if (!a.containsKey(k)) {
                    size++;
                }
            }
        }

        return this.size = size;
    }

    @Override
    public boolean isEmpty() {
        return first.isEmpty() && second.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return first.containsKey(key) || second.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return first.containsValue(value) || second.containsValue(value);
    }

    @Override
    public V get(Object key) {
        final V value = first.get(key);
        return value != null ? value : second.get(key);
    }

    @Override
    public V put(K key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        if (entrySet != null) {
            return entrySet;
        }

        // Check for dupes first to reduce allocations on the vastly more common case where there aren't any.
        boolean secondHasDupes = false;
        for (Entry<K, V> entry : second.entrySet()) {
            if (first.containsKey(entry.getKey())) {
                secondHasDupes = true;
                break;
            }
        }

        final Set<Entry<K, V>> filteredSecond;
        if (!secondHasDupes) {
            filteredSecond = second.entrySet();
        } else {
            filteredSecond = new LinkedHashSet<>();
            for (Entry<K, V> entry : second.entrySet()) {
                if (!first.containsKey(entry.getKey())) {
                    filteredSecond.add(entry);
                }
            }
        }
        return entrySet = Collections.unmodifiableSet(Sets.union(first.entrySet(), filteredSecond));
    }
}
