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

package com.linecorp.armeria.internal.common;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.handler.codec.Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

class ArmeriaHttp2Headers implements Http2Headers {

    @SuppressWarnings("unchecked")
    static <T> T unsafeCast(Object obj) {
        return (T) obj;
    }

    private final HttpHeadersBuilder delegate;

    ArmeriaHttp2Headers() {
        this(HttpHeaders.builder());
    }

    ArmeriaHttp2Headers(HttpHeadersBuilder delegate) {
        this.delegate = delegate;
    }

    HttpHeadersBuilder delegate() {
        return delegate;
    }

    @Nullable
    @Override
    public CharSequence get(CharSequence name) {
        return delegate.get(name);
    }

    @Override
    public CharSequence get(CharSequence name, CharSequence defaultValue) {
        return firstNonNull(get(name), defaultValue);
    }

    @Nullable
    @Override
    public CharSequence getAndRemove(CharSequence name) {
        return delegate.getAndRemove(name);
    }

    @Override
    public CharSequence getAndRemove(CharSequence name, CharSequence defaultValue) {
        return firstNonNull(getAndRemove(name), defaultValue);
    }

    @Override
    public List<CharSequence> getAll(CharSequence name) {
        return unsafeCast(delegate.getAll(name));
    }

    @Override
    public List<CharSequence> getAllAndRemove(CharSequence name) {
        return unsafeCast(delegate.getAllAndRemove(name));
    }

    @Nullable
    @Override
    public Boolean getBoolean(CharSequence name) {
        return delegate.getBoolean(name);
    }

    @Override
    public boolean getBoolean(CharSequence name, boolean defaultValue) {
        return delegate.getBoolean(name, defaultValue);
    }

    @Nullable
    @Override
    public Byte getByte(CharSequence name) {
        final Integer value = getInt(name);
        if (value != null) {
            return value.byteValue();
        }
        return null;
    }

    @Override
    public byte getByte(CharSequence name, byte defaultValue) {
        return firstNonNull(getByte(name), defaultValue);
    }

    @Nullable
    @Override
    public Character getChar(CharSequence name) {
        final Integer value = getInt(name);
        if (value != null) {
            return (char) value.intValue();
        }
        return null;
    }

    @Override
    public char getChar(CharSequence name, char defaultValue) {
        return firstNonNull(getChar(name), defaultValue);
    }

    @Nullable
    @Override
    public Short getShort(CharSequence name) {
        final Integer value = getInt(name);
        if (value != null) {
            return value.shortValue();
        }
        return null;
    }

    @Override
    public short getShort(CharSequence name, short defaultValue) {
        return firstNonNull(getShort(name), defaultValue);
    }

    @Nullable
    @Override
    public Integer getInt(CharSequence name) {
        return delegate.getInt(name);
    }

    @Override
    public int getInt(CharSequence name, int defaultValue) {
        return delegate.getInt(name, defaultValue);
    }

    @Nullable
    @Override
    public Long getLong(CharSequence name) {
        return delegate.getLong(name);
    }

    @Override
    public long getLong(CharSequence name, long defaultValue) {
        return delegate.getLong(name, defaultValue);
    }

    @Nullable
    @Override
    public Float getFloat(CharSequence name) {
        return delegate.getFloat(name);
    }

    @Override
    public float getFloat(CharSequence name, float defaultValue) {
        return delegate.getFloat(name, defaultValue);
    }

    @Nullable
    @Override
    public Double getDouble(CharSequence name) {
        return delegate.getDouble(name);
    }

    @Override
    public double getDouble(CharSequence name, double defaultValue) {
        return delegate.getDouble(name, defaultValue);
    }

    @Nullable
    @Override
    public Long getTimeMillis(CharSequence name) {
        return delegate.getTimeMillis(name);
    }

    @Override
    public long getTimeMillis(CharSequence name, long defaultValue) {
        return delegate.getTimeMillis(name, defaultValue);
    }

    @Nullable
    @Override
    public Boolean getBooleanAndRemove(CharSequence name) {
        final String value = delegate.getAndRemove(name);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return null;
    }

    @Override
    public boolean getBooleanAndRemove(CharSequence name, boolean defaultValue) {
        return firstNonNull(getBooleanAndRemove(name), defaultValue);
    }

    @Nullable
    @Override
    public Byte getByteAndRemove(CharSequence name) {
        final Integer value = delegate.getIntAndRemove(name);
        if (value != null) {
            return value.byteValue();
        }
        return null;
    }

    @Override
    public byte getByteAndRemove(CharSequence name, byte defaultValue) {
        return firstNonNull(getByteAndRemove(name), defaultValue);
    }

    @Nullable
    @Override
    public Character getCharAndRemove(CharSequence name) {
        final Integer value = delegate.getIntAndRemove(name);
        if (value != null) {
            return (char) value.intValue();
        }
        return null;
    }

    @Override
    public char getCharAndRemove(CharSequence name, char defaultValue) {
        return firstNonNull(getCharAndRemove(name), defaultValue);
    }

    @Nullable
    @Override
    public Short getShortAndRemove(CharSequence name) {
        final Integer value = delegate.getIntAndRemove(name);
        if (value != null) {
            return value.shortValue();
        }
        return null;
    }

    @Override
    public short getShortAndRemove(CharSequence name, short defaultValue) {
        return firstNonNull(getShortAndRemove(name), defaultValue);
    }

    @Nullable
    @Override
    public Integer getIntAndRemove(CharSequence name) {
        return delegate.getIntAndRemove(name);
    }

    @Override
    public int getIntAndRemove(CharSequence name, int defaultValue) {
        return delegate.getIntAndRemove(name, defaultValue);
    }

    @Nullable
    @Override
    public Long getLongAndRemove(CharSequence name) {
        return delegate.getLongAndRemove(name);
    }

    @Override
    public long getLongAndRemove(CharSequence name, long defaultValue) {
        return delegate.getLongAndRemove(name, defaultValue);
    }

    @Nullable
    @Override
    public Float getFloatAndRemove(CharSequence name) {
        return delegate.getFloatAndRemove(name);
    }

    @Override
    public float getFloatAndRemove(CharSequence name, float defaultValue) {
        return delegate.getFloatAndRemove(name, defaultValue);
    }

    @Nullable
    @Override
    public Double getDoubleAndRemove(CharSequence name) {
        return delegate.getDoubleAndRemove(name);
    }

    @Override
    public double getDoubleAndRemove(CharSequence name, double defaultValue) {
        return delegate.getDoubleAndRemove(name, defaultValue);
    }

    @Nullable
    @Override
    public Long getTimeMillisAndRemove(CharSequence name) {
        return delegate.getTimeMillisAndRemove(name);
    }

    @Override
    public long getTimeMillisAndRemove(CharSequence name, long defaultValue) {
        return delegate.getTimeMillisAndRemove(name, defaultValue);
    }

    @Override
    public boolean contains(CharSequence name) {
        return delegate.contains(name);
    }

    @Override
    public boolean contains(CharSequence name, CharSequence value) {
        return contains(name, value, false);
    }

    @Override
    public boolean contains(CharSequence name, CharSequence value, boolean caseInsensitive) {
        final List<String> values = delegate.getAll(name);
        if (values.isEmpty()) {
            return false;
        }

        for (String value0 : values) {
            final boolean result;
            if (caseInsensitive) {
                result = value0.equalsIgnoreCase(value.toString());
            } else {
                result = value0.contentEquals(value);
            }
            if (result) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsObject(CharSequence name, Object value) {
        return delegate.containsObject(name, value);
    }

    @Override
    public boolean containsBoolean(CharSequence name, boolean value) {
        return delegate.containsBoolean(name, value);
    }

    @Override
    public boolean containsByte(CharSequence name, byte value) {
        return getByte(name) == value;
    }

    @Override
    public boolean containsChar(CharSequence name, char value) {
        return getChar(name) == value;
    }

    @Override
    public boolean containsShort(CharSequence name, short value) {
        return getShort(name) == value;
    }

    @Override
    public boolean containsInt(CharSequence name, int value) {
        return delegate.containsInt(name, value);
    }

    @Override
    public boolean containsLong(CharSequence name, long value) {
        return delegate.containsLong(name, value);
    }

    @Override
    public boolean containsFloat(CharSequence name, float value) {
        return delegate.containsFloat(name, value);
    }

    @Override
    public boolean containsDouble(CharSequence name, double value) {
        return delegate.containsDouble(name, value);
    }

    @Override
    public boolean containsTimeMillis(CharSequence name, long value) {
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
    public Set<CharSequence> names() {
        return unsafeCast(delegate.names());
    }

    @Override
    public Http2Headers add(CharSequence name, CharSequence value) {
        final AsciiString headerName = HttpHeaderNames.of(name);
        delegate.add(headerName, ArmeriaHttpUtil.convertHeaderValue(headerName, value));
        return this;
    }

    @Override
    public Http2Headers add(CharSequence name, Iterable<? extends CharSequence> values) {
        for (CharSequence value : values) {
            add(name, value);
        }
        return this;
    }

    @Override
    public Http2Headers add(CharSequence name, CharSequence... values) {
        for (CharSequence value : values) {
            add(name, value);
        }
        return this;
    }

    @Override
    public Http2Headers add(Headers<? extends CharSequence, ? extends CharSequence, ?> headers) {
        if (headers == this) {
            throw new IllegalArgumentException("can't add to itself.");
        }

        for (Entry<? extends CharSequence, ? extends CharSequence> header : headers) {
            add(header.getKey(), header.getValue());
        }
        return this;
    }

    @Override
    public Http2Headers addObject(CharSequence name, Object value) {
        delegate.addObject(name, value);
        return this;
    }

    @Override
    public Http2Headers addObject(CharSequence name, Iterable<?> values) {
        delegate.addObject(name, values);
        return this;
    }

    @Override
    public Http2Headers addObject(CharSequence name, Object... values) {
        delegate.addObject(name, values);
        return this;
    }

    @Override
    public Http2Headers addBoolean(CharSequence name, boolean value) {
        delegate.add(name, String.valueOf(value));
        return this;
    }

    @Override
    public Http2Headers addByte(CharSequence name, byte value) {
        delegate.addInt(name, value);
        return this;
    }

    @Override
    public Http2Headers addChar(CharSequence name, char value) {
        delegate.addInt(name, value);
        return this;
    }

    @Override
    public Http2Headers addShort(CharSequence name, short value) {
        delegate.addInt(name, value);
        return this;
    }

    @Override
    public Http2Headers addInt(CharSequence name, int value) {
        delegate.addInt(name, value);
        return this;
    }

    @Override
    public Http2Headers addLong(CharSequence name, long value) {
        delegate.addLong(name, value);
        return this;
    }

    @Override
    public Http2Headers addFloat(CharSequence name, float value) {
        delegate.addFloat(name, value);
        return this;
    }

    @Override
    public Http2Headers addDouble(CharSequence name, double value) {
        delegate.addDouble(name, value);
        return this;
    }

    @Override
    public Http2Headers addTimeMillis(CharSequence name, long value) {
        delegate.addTimeMillis(name, value);
        return this;
    }

    @Override
    public Http2Headers set(CharSequence name, CharSequence value) {
        final AsciiString headerName = HttpHeaderNames.of(name);
        delegate.set(headerName, ArmeriaHttpUtil.convertHeaderValue(headerName, value));
        return this;
    }

    @Override
    public Http2Headers set(CharSequence name, Iterable<? extends CharSequence> values) {
        for (CharSequence value : values) {
            set(name, value);
        }
        return this;
    }

    @Override
    public Http2Headers set(Headers<? extends CharSequence, ? extends CharSequence, ?> headers) {
        if (headers != this) {
            clear();
            setAll(headers);
        }
        return this;
    }

    @Override
    public Http2Headers set(CharSequence name, CharSequence... values) {
        for (CharSequence value : values) {
            set(name, value);
        }
        return this;
    }

    @Override
    public Http2Headers setObject(CharSequence name, Object value) {
        delegate.setObject(name, value);
        return this;
    }

    @Override
    public Http2Headers setObject(CharSequence name, Iterable<?> values) {
        for (Object value : values) {
            setObject(name, value);
        }
        return this;
    }

    @Override
    public Http2Headers setObject(CharSequence name, Object... values) {
        for (Object value : values) {
            setObject(name, value);
        }
        return this;
    }

    @Override
    public Http2Headers setBoolean(CharSequence name, boolean value) {
        delegate.set(name, String.valueOf(value));
        return this;
    }

    @Override
    public Http2Headers setByte(CharSequence name, byte value) {
        delegate.setInt(name, value);
        return this;
    }

    @Override
    public Http2Headers setChar(CharSequence name, char value) {
        delegate.setInt(name, value);
        return this;
    }

    @Override
    public Http2Headers setShort(CharSequence name, short value) {
        delegate.setInt(name, value);
        return this;
    }

    @Override
    public Http2Headers setInt(CharSequence name, int value) {
        delegate.setInt(name, value);
        return this;
    }

    @Override
    public Http2Headers setLong(CharSequence name, long value) {
        delegate.setLong(name, value);
        return this;
    }

    @Override
    public Http2Headers setFloat(CharSequence name, float value) {
        delegate.setFloat(name, value);
        return this;
    }

    @Override
    public Http2Headers setDouble(CharSequence name, double value) {
        delegate.setDouble(name, value);
        return this;
    }

    @Override
    public Http2Headers setTimeMillis(CharSequence name, long value) {
        delegate.setTimeMillis(name, value);
        return this;
    }

    @Override
    public Http2Headers setAll(Headers<? extends CharSequence, ? extends CharSequence, ?> headers) {
        for (CharSequence name : headers.names()) {
           remove(name);
        }
        for (Entry<? extends CharSequence, ? extends CharSequence> header : headers) {
            add(header.getKey(), header.getValue());
        }
        return this;
    }

    @Override
    public boolean remove(CharSequence name) {
        return delegate.remove(name);
    }

    @Override
    public Http2Headers clear() {
        delegate.clear();
        return this;
    }

    @Override
    public Iterator<Entry<CharSequence, CharSequence>> iterator() {
        return unsafeCast(delegate.iterator());
    }

    @Override
    public void forEach(Consumer<? super Entry<CharSequence, CharSequence>> action) {
        delegate.forEach(entry -> action.accept(unsafeCast(entry)));
    }

    @Override
    public Spliterator<Entry<CharSequence, CharSequence>> spliterator() {
        return unsafeCast(delegate.spliterator());
    }

    @Override
    public Iterator<CharSequence> valueIterator(CharSequence name) {
        return unsafeCast(delegate.valueIterator(name));
    }

    @Override
    public Http2Headers method(CharSequence value) {
        return set(HttpHeaderNames.METHOD, value);
    }

    @Nullable
    @Override
    public CharSequence method() {
        return delegate.get(HttpHeaderNames.METHOD);
    }

    @Override
    public Http2Headers scheme(CharSequence value) {
        return set(HttpHeaderNames.SCHEME, value);
    }

    @Nullable
    @Override
    public CharSequence scheme() {
        return delegate.get(HttpHeaderNames.SCHEME);
    }

    @Override
    public Http2Headers authority(CharSequence value) {
        return set(HttpHeaderNames.AUTHORITY, value);
    }

    @Nullable
    @Override
    public CharSequence authority() {
        return delegate.get(HttpHeaderNames.AUTHORITY);
    }

    @Override
    public Http2Headers path(CharSequence value) {
        return set(HttpHeaderNames.PATH, value);
    }

    @Nullable
    @Override
    public CharSequence path() {
        return delegate.get(HttpHeaderNames.PATH);
    }

    @Override
    public Http2Headers status(CharSequence value) {
        return set(HttpHeaderNames.STATUS, value);
    }

    @Nullable
    @Override
    public CharSequence status() {
        return delegate.get(HttpHeaderNames.STATUS);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ArmeriaHttp2Headers)) {
            return false;
        }
        final ArmeriaHttp2Headers that = (ArmeriaHttp2Headers) obj;

        return delegate.equals(that.delegate());
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
