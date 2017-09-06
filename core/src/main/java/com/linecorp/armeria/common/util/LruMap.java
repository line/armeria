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

package com.linecorp.armeria.common.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU (Least Recently Used) cache {@link Map}.
 *
 * @param <T> the key type
 * @param <U> the value type
 */
public class LruMap<T, U> extends LinkedHashMap<T, U> {
    private static final long serialVersionUID = 5358379908010214089L;

    private final int maxEntries;

    /**
     * Creates a new instance with the specified maximum number of allowed entries.
     */
    public LruMap(int maxEntries) {
        super(maxEntries, 0.75f, true);
        this.maxEntries = maxEntries;
    }

    /**
     * Returns {@code true} if the {@link #size()} of this map exceeds the maximum number of allowed entries.
     * Invoked by {@link LinkedHashMap} for LRU behavior.
     */
    @Override
    protected boolean removeEldestEntry(Map.Entry<T, U> eldest) {
        return size() > maxEntries;
    }
}
