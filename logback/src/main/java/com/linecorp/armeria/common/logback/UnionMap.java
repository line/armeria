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
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.linecorp.armeria.common.annotation.Nullable;

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
        final Set<Entry<K, V>> secondEntries = second.entrySet();
        for (Entry<K, V> entry : secondEntries) {
            if (first.containsKey(entry.getKey())) {
                secondHasDupes = true;
                break;
            }
        }

        final Set<Entry<K, V>> filteredSecond;
        if (!secondHasDupes) {
            filteredSecond = secondEntries;
        } else {
            filteredSecond = new LinkedHashSet<>();
            for (Entry<K, V> entry : secondEntries) {
                if (!first.containsKey(entry.getKey())) {
                    filteredSecond.add(entry);
                }
            }
        }
        return entrySet = new ConcatenatedSet<>(first.entrySet(), filteredSecond);
    }

    private static final class ConcatenatedSet<T> extends AbstractSet<T> {

        private final Set<T> first;
        private final Set<T> second;

        private final int size;

        ConcatenatedSet(Set<T> first, Set<T> second) {
            this.first = first;
            this.second = second;

            size = first.size() + second.size();
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean add(T t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addAll(Collection<? extends T> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(Collection<?> coll) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeIf(Predicate<? super T> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return new Iterator<T>() {

                final Iterator<T> firstItr = first.iterator();
                final Iterator<T> secondItr = second.iterator();

                @Override
                public boolean hasNext() {
                    return firstItr.hasNext() || secondItr.hasNext();
                }

                @Override
                public T next() {
                    if (firstItr.hasNext()) {
                        return firstItr.next();
                    }
                    return secondItr.next();
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
