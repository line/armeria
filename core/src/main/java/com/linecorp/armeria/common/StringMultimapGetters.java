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
package com.linecorp.armeria.common;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Provides the getter methods to {@link StringMultimap}.
 *
 * @param <IN_NAME> the type of the user-specified names, which may be more permissive than {@link NAME}
 * @param <NAME> the actual type of the names
 */
interface StringMultimapGetters<IN_NAME extends CharSequence, NAME extends IN_NAME>
        extends Iterable<Entry<NAME, String>> {

    @Nullable
    String get(IN_NAME name);

    String get(IN_NAME name, String defaultValue);

    @Nullable
    String getLast(IN_NAME name);

    String getLast(IN_NAME name, String defaultValue);

    List<String> getAll(IN_NAME name);

    @Nullable
    Boolean getBoolean(IN_NAME name);

    boolean getBoolean(IN_NAME name, boolean defaultValue);

    @Nullable
    Boolean getLastBoolean(IN_NAME name);

    boolean getLastBoolean(IN_NAME name, boolean defaultValue);

    @Nullable
    Integer getInt(IN_NAME name);

    int getInt(IN_NAME name, int defaultValue);

    @Nullable
    Integer getLastInt(IN_NAME name);

    int getLastInt(IN_NAME name, int defaultValue);

    @Nullable
    Long getLong(IN_NAME name);

    long getLong(IN_NAME name, long defaultValue);

    @Nullable
    Long getLastLong(IN_NAME name);

    long getLastLong(IN_NAME name, long defaultValue);

    @Nullable
    Float getFloat(IN_NAME name);

    float getFloat(IN_NAME name, float defaultValue);

    @Nullable
    Float getLastFloat(IN_NAME name);

    float getLastFloat(IN_NAME name, float defaultValue);

    @Nullable
    Double getDouble(IN_NAME name);

    double getDouble(IN_NAME name, double defaultValue);

    @Nullable
    Double getLastDouble(IN_NAME name);

    double getLastDouble(IN_NAME name, double defaultValue);

    @Nullable
    Long getTimeMillis(IN_NAME name);

    long getTimeMillis(IN_NAME name, long defaultValue);

    @Nullable
    Long getLastTimeMillis(IN_NAME name);

    long getLastTimeMillis(IN_NAME name, long defaultValue);

    boolean contains(IN_NAME name);

    boolean contains(IN_NAME name, String value);

    boolean containsObject(IN_NAME name, Object value);

    boolean containsBoolean(IN_NAME name, boolean value);

    boolean containsInt(IN_NAME name, int value);

    boolean containsLong(IN_NAME name, long value);

    boolean containsFloat(IN_NAME name, float value);

    boolean containsDouble(IN_NAME name, double value);

    boolean containsTimeMillis(IN_NAME name, long value);

    int size();

    boolean isEmpty();

    Set<NAME> names();

    @Override
    Iterator<Entry<NAME, String>> iterator();

    Iterator<String> valueIterator(IN_NAME name);

    void forEach(BiConsumer<NAME, String> action);

    void forEachValue(IN_NAME name, Consumer<String> action);

    Stream<Entry<NAME, String>> stream();

    Stream<String> valueStream(IN_NAME name);
}
