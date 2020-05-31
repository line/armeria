/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.servlet.util;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * This Map implementation is generally not thread-safe. It is primarily designed
 * for data structures exposed from request objects, for use in a single thread only.
 * @param <K> the key type
 * @param <V> the value element type
 */
public class LinkedMultiValueMap<K, V> implements Serializable, Cloneable {
    private static final long serialVersionUID = 5338250261420072687L;

    // Forked from https://github.com/spring-projects/spring-framework/blob/master/spring-core/src/main/
    // java/org/springframework/util/LinkedMultiValueMap.java

    private final Map<K, List<V>> targetMap;

    /**
     * Create a new LinkedMultiValueMap that wraps a {@link LinkedHashMap}.
     */
    public LinkedMultiValueMap() {
        targetMap = new LinkedHashMap<>();
    }

    /**
     * Creates a new instance.
     */
    public LinkedMultiValueMap(Map<K, List<V>> otherMap) {
        targetMap = new LinkedHashMap<>(otherMap);
    }

    /**
     * Get first value.
     */
    @Nullable
    public V getFirst(K key) {
        requireNonNull(key, "key");
        final List<V> values = targetMap.get(key);
        return values != null ? values.get(0) : null;
    }

    /**
     * Add item.
     */
    public void add(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        final List<V> values = targetMap.computeIfAbsent(key, k -> new LinkedList<>());
        values.add(value);
    }

    /**
     * Add all items.
     */
    public void addAll(K key, List<? extends V> values) {
        requireNonNull(key, "key");
        requireNonNull(values, "values");
        final List<V> currentValues = targetMap.computeIfAbsent(key, k -> new LinkedList<>());
        currentValues.addAll(values);
    }

    /**
     * Set value.
     */
    public void set(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        final List<V> values = new LinkedList<>();
        values.add(value);
        targetMap.put(key, values);
    }

    /**
     * Set all values.
     */
    public void setAll(Map<K, V> values) {
        requireNonNull(values, "values");
        values.forEach(this::set);
    }

    /**
     * Convert to single value map.
     */
    public Map<K, V> toSingleValueMap() {
        final LinkedHashMap<K, V> singleValueMap = new LinkedHashMap<>(targetMap.size());
        targetMap.forEach((key, value) -> singleValueMap.put(key, value.get(0)));
        return singleValueMap;
    }

    /**
     * Get size.
     */
    public int size() {
        return targetMap.size();
    }

    /**
     * Check is empty.
     */
    public boolean isEmpty() {
        return targetMap.isEmpty();
    }

    /**
     * Check contains key.
     */
    public boolean containsKey(Object key) {
        requireNonNull(key, "key");
        return targetMap.containsKey(key);
    }

    /**
     * Creates a new instance.
     */
    public boolean containsValue(Object value) {
        requireNonNull(value, "value");
        return targetMap.containsValue(value);
    }

    /**
     * Get by key.
     */
    @Nullable
    public List<V> get(Object key) {
        requireNonNull(key, "key");
        return targetMap.get(key);
    }

    /**
     * Put value.
     */
    public List<V> put(K key, List<V> value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        return requireNonNull(targetMap.put(key, value));
    }

    /**
     * Remove by key.
     */
    public List<V> remove(Object key) {
        requireNonNull(key, "key");
        return targetMap.remove(key);
    }

    /**
     * Put all values.
     */
    public void putAll(Map<? extends K, ? extends List<V>> map) {
        requireNonNull(map, "map");
        targetMap.putAll(map);
    }

    /**
     * Clear map.
     */
    public void clear() {
        targetMap.clear();
    }

    /**
     * Get key set.
     */
    public Set<K> keySet() {
        return targetMap.keySet();
    }

    /**
     * Get all values.
     */
    public Collection<List<V>> values() {
        return targetMap.values();
    }

    /**
     * Get entry set.
     */
    public Set<Map.Entry<K, List<V>>> entrySet() {
        return targetMap.entrySet();
    }

    @Override
    public boolean equals(Object obj) {
        requireNonNull(obj, "obj");
        return targetMap.equals(obj);
    }

    @Override
    public int hashCode() {
        return targetMap.hashCode();
    }

    @Override
    public String toString() {
        return targetMap.toString();
    }

    @Override
    public LinkedMultiValueMap<K, V> clone() throws CloneNotSupportedException {
        return (LinkedMultiValueMap) super.clone();
    }
}
