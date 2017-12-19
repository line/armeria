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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import io.netty.handler.codec.Headers;

final class ImmutableHttpParameters implements HttpParameters {

    private final HttpParameters delegate;

    ImmutableHttpParameters(HttpParameters delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    public String get(String name) {
        return delegate.get(name);
    }

    @Override
    public String get(String name, String defaultValue) {
        return delegate.get(name, defaultValue);
    }

    @Override
    public String getAndRemove(String name) {
        return unsupported();
    }

    @Override
    public String getAndRemove(String name, String defaultValue) {
        return unsupported();
    }

    @Override
    public List<String> getAll(String name) {
        return delegate.getAll(name);
    }

    @Override
    public List<String> getAllAndRemove(String name) {
        return unsupported();
    }

    @Override
    public Boolean getBoolean(String name) {
        return delegate.getBoolean(name);
    }

    @Override
    public boolean getBoolean(String name, boolean defaultValue) {
        return delegate.getBoolean(name, defaultValue);
    }

    @Override
    public Byte getByte(String name) {
        return delegate.getByte(name);
    }

    @Override
    public byte getByte(String name, byte defaultValue) {
        return delegate.getByte(name, defaultValue);
    }

    @Override
    public Character getChar(String name) {
        return delegate.getChar(name);
    }

    @Override
    public char getChar(String name, char defaultValue) {
        return delegate.getChar(name, defaultValue);
    }

    @Override
    public Short getShort(String name) {
        return delegate.getShort(name);
    }

    @Override
    public short getShort(String name, short defaultValue) {
        return delegate.getShort(name, defaultValue);
    }

    @Override
    public Integer getInt(String name) {
        return delegate.getInt(name);
    }

    @Override
    public int getInt(String name, int defaultValue) {
        return delegate.getInt(name, defaultValue);
    }

    @Override
    public Long getLong(String name) {
        return delegate.getLong(name);
    }

    @Override
    public long getLong(String name, long defaultValue) {
        return delegate.getLong(name, defaultValue);
    }

    @Override
    public Float getFloat(String name) {
        return delegate.getFloat(name);
    }

    @Override
    public float getFloat(String name, float defaultValue) {
        return delegate.getFloat(name, defaultValue);
    }

    @Override
    public Double getDouble(String name) {
        return delegate.getDouble(name);
    }

    @Override
    public double getDouble(String name, double defaultValue) {
        return delegate.getDouble(name, defaultValue);
    }

    @Override
    public Long getTimeMillis(String name) {
        return delegate.getTimeMillis(name);
    }

    @Override
    public long getTimeMillis(String name, long defaultValue) {
        return delegate.getTimeMillis(name, defaultValue);
    }

    @Override
    public Boolean getBooleanAndRemove(String name) {
        return unsupported();
    }

    @Override
    public boolean getBooleanAndRemove(String name, boolean defaultValue) {
        return unsupported();
    }

    @Override
    public Byte getByteAndRemove(String name) {
        return unsupported();
    }

    @Override
    public byte getByteAndRemove(String name, byte defaultValue) {
        return unsupported();
    }

    @Override
    public Character getCharAndRemove(String name) {
        return unsupported();
    }

    @Override
    public char getCharAndRemove(String name, char defaultValue) {
        return unsupported();
    }

    @Override
    public Short getShortAndRemove(String name) {
        return unsupported();
    }

    @Override
    public short getShortAndRemove(String name, short defaultValue) {
        return unsupported();
    }

    @Override
    public Integer getIntAndRemove(String name) {
        return unsupported();
    }

    @Override
    public int getIntAndRemove(String name, int defaultValue) {
        return unsupported();
    }

    @Override
    public Long getLongAndRemove(String name) {
        return unsupported();
    }

    @Override
    public long getLongAndRemove(String name, long defaultValue) {
        return unsupported();
    }

    @Override
    public Float getFloatAndRemove(String name) {
        return unsupported();
    }

    @Override
    public float getFloatAndRemove(String name, float defaultValue) {
        return unsupported();
    }

    @Override
    public Double getDoubleAndRemove(String name) {
        return unsupported();
    }

    @Override
    public double getDoubleAndRemove(String name, double defaultValue) {
        return unsupported();
    }

    @Override
    public Long getTimeMillisAndRemove(String name) {
        return unsupported();
    }

    @Override
    public long getTimeMillisAndRemove(String name, long defaultValue) {
        return unsupported();
    }

    @Override
    public boolean contains(String name) {
        return delegate.contains(name);
    }

    @Override
    public boolean contains(String name, String value) {
        return delegate.contains(name, value);
    }

    @Override
    public boolean containsObject(String name, Object value) {
        return delegate.containsObject(name, value);
    }

    @Override
    public boolean containsBoolean(String name, boolean value) {
        return delegate.containsBoolean(name, value);
    }

    @Override
    public boolean containsByte(String name, byte value) {
        return delegate.containsByte(name, value);
    }

    @Override
    public boolean containsChar(String name, char value) {
        return delegate.containsChar(name, value);
    }

    @Override
    public boolean containsShort(String name, short value) {
        return delegate.containsShort(name, value);
    }

    @Override
    public boolean containsInt(String name, int value) {
        return delegate.containsInt(name, value);
    }

    @Override
    public boolean containsLong(String name, long value) {
        return delegate.containsLong(name, value);
    }

    @Override
    public boolean containsFloat(String name, float value) {
        return delegate.containsFloat(name, value);
    }

    @Override
    public boolean containsDouble(String name, double value) {
        return delegate.containsDouble(name, value);
    }

    @Override
    public boolean containsTimeMillis(String name, long value) {
        return delegate.containsTimeMillis(name, value);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public Set<String> names() {
        return delegate.names();
    }

    @Override
    public HttpParameters add(String name, String value) {
        return unsupported();
    }

    @Override
    public HttpParameters add(String name, Iterable<? extends String> values) {
        return unsupported();
    }

    @Override
    public HttpParameters add(String name, String... values) {
        return unsupported();
    }

    @Override
    public HttpParameters add(Headers<? extends String, ? extends String, ?> headers) {
        return unsupported();
    }

    @Override
    public HttpParameters addObject(String name, Object value) {
        return unsupported();
    }

    @Override
    public HttpParameters addObject(String name, Iterable<?> values) {
        return unsupported();
    }

    @Override
    public HttpParameters addObject(String name, Object... values) {
        return unsupported();
    }

    @Override
    public HttpParameters addBoolean(String name, boolean value) {
        return unsupported();
    }

    @Override
    public HttpParameters addByte(String name, byte value) {
        return unsupported();
    }

    @Override
    public HttpParameters addChar(String name, char value) {
        return unsupported();
    }

    @Override
    public HttpParameters addShort(String name, short value) {
        return unsupported();
    }

    @Override
    public HttpParameters addInt(String name, int value) {
        return unsupported();
    }

    @Override
    public HttpParameters addLong(String name, long value) {
        return unsupported();
    }

    @Override
    public HttpParameters addFloat(String name, float value) {
        return unsupported();
    }

    @Override
    public HttpParameters addDouble(String name, double value) {
        return unsupported();
    }

    @Override
    public HttpParameters addTimeMillis(String name, long value) {
        return unsupported();
    }

    @Override
    public HttpParameters set(String name, String value) {
        return unsupported();
    }

    @Override
    public HttpParameters set(String name, Iterable<? extends String> values) {
        return unsupported();
    }

    @Override
    public HttpParameters set(String name, String... values) {
        return unsupported();
    }

    @Override
    public HttpParameters set(Headers<? extends String, ? extends String, ?> headers) {
        return unsupported();
    }

    @Override
    public HttpParameters setObject(String name, Object value) {
        return unsupported();
    }

    @Override
    public HttpParameters setObject(String name, Iterable<?> values) {
        return unsupported();
    }

    @Override
    public HttpParameters setObject(String name, Object... values) {
        return unsupported();
    }

    @Override
    public HttpParameters setBoolean(String name, boolean value) {
        return unsupported();
    }

    @Override
    public HttpParameters setByte(String name, byte value) {
        return unsupported();
    }

    @Override
    public HttpParameters setChar(String name, char value) {
        return unsupported();
    }

    @Override
    public HttpParameters setShort(String name, short value) {
        return unsupported();
    }

    @Override
    public HttpParameters setInt(String name, int value) {
        return unsupported();
    }

    @Override
    public HttpParameters setLong(String name, long value) {
        return unsupported();
    }

    @Override
    public HttpParameters setFloat(String name, float value) {
        return unsupported();
    }

    @Override
    public HttpParameters setDouble(String name, double value) {
        return unsupported();
    }

    @Override
    public HttpParameters setTimeMillis(String name, long value) {
        return unsupported();
    }

    @Override
    public HttpParameters setAll(Headers<? extends String, ? extends String, ?> headers) {
        return unsupported();
    }

    @Override
    public boolean remove(String name) {
        return unsupported();
    }

    @Override
    public HttpParameters clear() {
        return unsupported();
    }

    @Override
    public Iterator<Entry<String, String>> iterator() {
        return delegate.iterator();
    }

    @Override
    public HttpParameters asImmutable() {
        return this;
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    public boolean equals(Object obj) {
        return delegate.equals(obj);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    private static <T> T unsupported() {
        throw new UnsupportedOperationException("immutable");
    }
}
