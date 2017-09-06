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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import io.netty.handler.codec.Headers;
import io.netty.util.AsciiString;

final class ImmutableHttpHeaders implements HttpHeaders {

    private final HttpHeaders delegate;

    ImmutableHttpHeaders(HttpHeaders delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    public HttpMethod method() {
        return delegate.method();
    }

    @Override
    public HttpHeaders method(HttpMethod method) {
        return unsupported();
    }

    @Override
    public String scheme() {
        return delegate.scheme();
    }

    @Override
    public HttpHeaders scheme(String scheme) {
        return unsupported();
    }

    @Override
    public String authority() {
        return delegate.authority();
    }

    @Override
    public HttpHeaders authority(String authority) {
        return unsupported();
    }

    @Override
    public String path() {
        return delegate.path();
    }

    @Override
    public HttpHeaders path(String path) {
        return unsupported();
    }

    @Override
    public HttpStatus status() {
        return delegate.status();
    }

    @Override
    public HttpHeaders status(int statusCode) {
        return unsupported();
    }

    @Override
    public HttpHeaders status(HttpStatus status) {
        return unsupported();
    }

    @Override
    public boolean isEndOfStream() {
        return delegate.isEndOfStream();
    }

    @Override
    public String get(AsciiString name) {
        return delegate.get(name);
    }

    @Override
    public String get(AsciiString name, String defaultValue) {
        return delegate.get(name, defaultValue);
    }

    @Override
    public String getAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public String getAndRemove(AsciiString name, String defaultValue) {
        return unsupported();
    }

    @Override
    public List<String> getAll(AsciiString name) {
        return delegate.getAll(name);
    }

    @Override
    public List<String> getAllAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public Boolean getBoolean(AsciiString name) {
        return delegate.getBoolean(name);
    }

    @Override
    public boolean getBoolean(AsciiString name, boolean defaultValue) {
        return delegate.getBoolean(name, defaultValue);
    }

    @Override
    public Byte getByte(AsciiString name) {
        return delegate.getByte(name);
    }

    @Override
    public byte getByte(AsciiString name, byte defaultValue) {
        return delegate.getByte(name, defaultValue);
    }

    @Override
    public Character getChar(AsciiString name) {
        return delegate.getChar(name);
    }

    @Override
    public char getChar(AsciiString name, char defaultValue) {
        return delegate.getChar(name, defaultValue);
    }

    @Override
    public Short getShort(AsciiString name) {
        return delegate.getShort(name);
    }

    @Override
    public short getShort(AsciiString name, short defaultValue) {
        return delegate.getShort(name, defaultValue);
    }

    @Override
    public Integer getInt(AsciiString name) {
        return delegate.getInt(name);
    }

    @Override
    public int getInt(AsciiString name, int defaultValue) {
        return delegate.getInt(name, defaultValue);
    }

    @Override
    public Long getLong(AsciiString name) {
        return delegate.getLong(name);
    }

    @Override
    public long getLong(AsciiString name, long defaultValue) {
        return delegate.getLong(name, defaultValue);
    }

    @Override
    public Float getFloat(AsciiString name) {
        return delegate.getFloat(name);
    }

    @Override
    public float getFloat(AsciiString name, float defaultValue) {
        return delegate.getFloat(name, defaultValue);
    }

    @Override
    public Double getDouble(AsciiString name) {
        return delegate.getDouble(name);
    }

    @Override
    public double getDouble(AsciiString name, double defaultValue) {
        return delegate.getDouble(name, defaultValue);
    }

    @Override
    public Long getTimeMillis(AsciiString name) {
        return delegate.getTimeMillis(name);
    }

    @Override
    public long getTimeMillis(AsciiString name, long defaultValue) {
        return delegate.getTimeMillis(name, defaultValue);
    }

    @Override
    public Boolean getBooleanAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public boolean getBooleanAndRemove(AsciiString name, boolean defaultValue) {
        return unsupported();
    }

    @Override
    public Byte getByteAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public byte getByteAndRemove(AsciiString name, byte defaultValue) {
        return unsupported();
    }

    @Override
    public Character getCharAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public char getCharAndRemove(AsciiString name, char defaultValue) {
        return unsupported();
    }

    @Override
    public Short getShortAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public short getShortAndRemove(AsciiString name, short defaultValue) {
        return unsupported();
    }

    @Override
    public Integer getIntAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public int getIntAndRemove(AsciiString name, int defaultValue) {
        return unsupported();
    }

    @Override
    public Long getLongAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public long getLongAndRemove(AsciiString name, long defaultValue) {
        return unsupported();
    }

    @Override
    public Float getFloatAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public float getFloatAndRemove(AsciiString name, float defaultValue) {
        return unsupported();
    }

    @Override
    public Double getDoubleAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public double getDoubleAndRemove(AsciiString name, double defaultValue) {
        return unsupported();
    }

    @Override
    public Long getTimeMillisAndRemove(AsciiString name) {
        return unsupported();
    }

    @Override
    public long getTimeMillisAndRemove(AsciiString name, long defaultValue) {
        return unsupported();
    }

    @Override
    public boolean contains(AsciiString name) {
        return delegate.contains(name);
    }

    @Override
    public boolean contains(AsciiString name, String value) {
        return delegate.contains(name, value);
    }

    @Override
    public boolean containsObject(AsciiString name, Object value) {
        return delegate.containsObject(name, value);
    }

    @Override
    public boolean containsBoolean(AsciiString name, boolean value) {
        return delegate.containsBoolean(name, value);
    }

    @Override
    public boolean containsByte(AsciiString name, byte value) {
        return delegate.containsByte(name, value);
    }

    @Override
    public boolean containsChar(AsciiString name, char value) {
        return delegate.containsChar(name, value);
    }

    @Override
    public boolean containsShort(AsciiString name, short value) {
        return delegate.containsShort(name, value);
    }

    @Override
    public boolean containsInt(AsciiString name, int value) {
        return delegate.containsInt(name, value);
    }

    @Override
    public boolean containsLong(AsciiString name, long value) {
        return delegate.containsLong(name, value);
    }

    @Override
    public boolean containsFloat(AsciiString name, float value) {
        return delegate.containsFloat(name, value);
    }

    @Override
    public boolean containsDouble(AsciiString name, double value) {
        return delegate.containsDouble(name, value);
    }

    @Override
    public boolean containsTimeMillis(AsciiString name, long value) {
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
    public Set<AsciiString> names() {
        return delegate.names();
    }

    @Override
    public HttpHeaders add(AsciiString name, String value) {
        return unsupported();
    }

    @Override
    public HttpHeaders add(AsciiString name, Iterable<? extends String> values) {
        return unsupported();
    }

    @Override
    public HttpHeaders add(AsciiString name, String... values) {
        return unsupported();
    }

    @Override
    public HttpHeaders add(
            Headers<? extends AsciiString, ? extends String, ?> headers) {
        return unsupported();
    }

    @Override
    public HttpHeaders addObject(AsciiString name, Object value) {
        return unsupported();
    }

    @Override
    public HttpHeaders addObject(AsciiString name, Iterable<?> values) {
        return unsupported();
    }

    @Override
    public HttpHeaders addObject(AsciiString name, Object... values) {
        return unsupported();
    }

    @Override
    public HttpHeaders addBoolean(AsciiString name, boolean value) {
        return unsupported();
    }

    @Override
    public HttpHeaders addByte(AsciiString name, byte value) {
        return unsupported();
    }

    @Override
    public HttpHeaders addChar(AsciiString name, char value) {
        return unsupported();
    }

    @Override
    public HttpHeaders addShort(AsciiString name, short value) {
        return unsupported();
    }

    @Override
    public HttpHeaders addInt(AsciiString name, int value) {
        return unsupported();
    }

    @Override
    public HttpHeaders addLong(AsciiString name, long value) {
        return unsupported();
    }

    @Override
    public HttpHeaders addFloat(AsciiString name, float value) {
        return unsupported();
    }

    @Override
    public HttpHeaders addDouble(AsciiString name, double value) {
        return unsupported();
    }

    @Override
    public HttpHeaders addTimeMillis(AsciiString name, long value) {
        return unsupported();
    }

    @Override
    public HttpHeaders set(AsciiString name, String value) {
        return unsupported();
    }

    @Override
    public HttpHeaders set(AsciiString name, Iterable<? extends String> values) {
        return unsupported();
    }

    @Override
    public HttpHeaders set(AsciiString name, String... values) {
        return unsupported();
    }

    @Override
    public HttpHeaders set(
            Headers<? extends AsciiString, ? extends String, ?> headers) {
        return unsupported();
    }

    @Override
    public HttpHeaders setObject(AsciiString name, Object value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setObject(AsciiString name, Iterable<?> values) {
        return unsupported();
    }

    @Override
    public HttpHeaders setObject(AsciiString name, Object... values) {
        return unsupported();
    }

    @Override
    public HttpHeaders setBoolean(AsciiString name, boolean value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setByte(AsciiString name, byte value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setChar(AsciiString name, char value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setShort(AsciiString name, short value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setInt(AsciiString name, int value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setLong(AsciiString name, long value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setFloat(AsciiString name, float value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setDouble(AsciiString name, double value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setTimeMillis(AsciiString name, long value) {
        return unsupported();
    }

    @Override
    public HttpHeaders setAll(
            Headers<? extends AsciiString, ? extends String, ?> headers) {
        return unsupported();
    }

    @Override
    public boolean remove(AsciiString name) {
        return unsupported();
    }

    @Override
    public HttpHeaders clear() {
        return unsupported();
    }

    @Override
    public Iterator<Entry<AsciiString, String>> iterator() {
        return delegate.iterator();
    }

    @Override
    public HttpHeaders asImmutable() {
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
