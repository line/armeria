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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.ArmeriaHttpUtil.isAbsoluteUri;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.hasPseudoHeaderFormat;
import static io.netty.util.internal.MathUtil.findNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.math.IntMath;

import io.netty.handler.codec.DateFormatter;
import io.netty.util.AsciiString;

/**
 * The base container implementation of HTTP/2 headers.
 */
class HttpHeadersBase implements HttpHeaderGetters {

    private static final int PROHIBITED_VALUE_CHAR_MASK = ~15;
    private static final BitSet PROHIBITED_VALUE_CHARS = new BitSet(~PROHIBITED_VALUE_CHAR_MASK + 1);
    private static final String[] PROHIBITED_VALUE_CHAR_NAMES = new String[~PROHIBITED_VALUE_CHAR_MASK + 1];

    static {
        PROHIBITED_VALUE_CHARS.set(0);
        PROHIBITED_VALUE_CHARS.set('\n');
        PROHIBITED_VALUE_CHARS.set(0xB);
        PROHIBITED_VALUE_CHARS.set('\f');
        PROHIBITED_VALUE_CHARS.set('\r');
        PROHIBITED_VALUE_CHAR_NAMES[0] = "<NUL>";
        PROHIBITED_VALUE_CHAR_NAMES['\n'] = "<LF>";
        PROHIBITED_VALUE_CHAR_NAMES[0xB] = "<VT>";
        PROHIBITED_VALUE_CHAR_NAMES['\f'] = "<FF>";
        PROHIBITED_VALUE_CHAR_NAMES['\r'] = "<CR>";
    }

    static final int DEFAULT_SIZE_HINT = 16;

    /**
     * Constant used to seed the hash code generation. Could be anything but this was borrowed from murmur3.
     */
    static final int HASH_CODE_SEED = 0xc2b2ae35;

    // XXX(anuraaga): It could be an interesting future optimization if we can use something similar
    //                to an EnumSet when it's applicable, with just one each of commonly known header names.
    //                It should be very common.
    @VisibleForTesting
    final HeaderEntry[] entries;
    private final byte hashMask;

    private final HeaderEntry head;
    private HeaderEntry firstNonPseudo;

    int size;
    private boolean endOfStream;

    HttpHeadersBase(int sizeHint) {
        // Enforce a bound of [2, 128] because hashMask is a byte. The max possible value of hashMask is
        // one less than the length of this array, and we want the mask to be > 0.
        entries = new HeaderEntry[findNextPositivePowerOfTwo(max(2, min(sizeHint, 128)))];
        hashMask = (byte) (entries.length - 1);
        head = firstNonPseudo = new HeaderEntry();
    }

    /**
     * Creates a shallow or deep copy of the specified {@link HttpHeadersBase}.
     */
    HttpHeadersBase(HttpHeadersBase headers, boolean shallowCopy) {
        hashMask = headers.hashMask;
        endOfStream = headers.endOfStream;

        if (shallowCopy) {
            entries = headers.entries;
            head = headers.head;
            firstNonPseudo = headers.firstNonPseudo;
            size = headers.size;
        } else {
            entries = new HeaderEntry[headers.entries.length];
            head = firstNonPseudo = new HeaderEntry();
            final boolean succeeded = addFast(headers);
            assert succeeded;
        }
    }

    /**
     * Creates a deep copy of the specified {@link HttpHeaderGetters}.
     */
    HttpHeadersBase(HttpHeaderGetters headers) {
        this(headers.size());
        assert !(headers instanceof HttpHeadersBase);
        addSlow(headers);
    }

    // Shortcut methods

    URI uri() {
        final String uri;
        final String path = path();
        if (isAbsoluteUri(path)) {
            uri = path;
        } else {
            final String scheme = scheme();
            checkState(scheme != null, ":scheme header does not exist.");
            final String authority = authority();
            checkState(authority != null, ":authority header does not exist.");
            uri = scheme + "://" + authority + path;
        }

        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("not a valid URI: " + uri, e);
        }
    }

    HttpMethod method() {
        final String methodStr = get(HttpHeaderNames.METHOD);
        checkState(methodStr != null, ":method header does not exist.");
        return HttpMethod.isSupported(methodStr) ? HttpMethod.valueOf(methodStr)
                                                 : HttpMethod.UNKNOWN;
    }

    final void method(HttpMethod method) {
        requireNonNull(method, "method");
        set(HttpHeaderNames.METHOD, method.name());
    }

    @Nullable
    String scheme() {
        return get(HttpHeaderNames.SCHEME);
    }

    final void scheme(String scheme) {
        requireNonNull(scheme, "scheme");
        set(HttpHeaderNames.SCHEME, scheme);
    }

    @Nullable
    String authority() {
        return get(HttpHeaderNames.AUTHORITY);
    }

    final void authority(String authority) {
        requireNonNull(authority, "authority");
        set(HttpHeaderNames.AUTHORITY, authority);
    }

    String path() {
        final String path = get(HttpHeaderNames.PATH);
        checkState(path != null, ":path header does not exist.");
        return path;
    }

    final void path(String path) {
        requireNonNull(path, "path");
        set(HttpHeaderNames.PATH, path);
    }

    HttpStatus status() {
        final String statusStr = get(HttpHeaderNames.STATUS);
        checkState(statusStr != null, ":status header does not exist.");
        return HttpStatus.valueOf(statusStr);
    }

    final void status(int statusCode) {
        status(HttpStatus.valueOf(statusCode));
    }

    final void status(HttpStatus status) {
        requireNonNull(status, "status");
        set(HttpHeaderNames.STATUS, status.codeAsText());
    }

    @Nullable
    @Override
    public MediaType contentType() {
        final String contentTypeString = get(HttpHeaderNames.CONTENT_TYPE);
        if (contentTypeString == null) {
            return null;
        }

        try {
            return MediaType.parse(contentTypeString);
        } catch (IllegalArgumentException unused) {
            // Invalid media type
            return null;
        }
    }

    final void contentType(MediaType contentType) {
        requireNonNull(contentType, "contentType");
        set(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
    }

    // Getters

    @Override
    public final boolean isEndOfStream() {
        return endOfStream;
    }

    @Nullable
    @Override
    public final String get(CharSequence name) {
        requireNonNull(name, "name");
        final int h = AsciiString.hashCode(name);
        final int i = index(h);
        HeaderEntry e = entries[i];
        String value = null;
        // loop until the first header was found
        while (e != null) {
            if (e.hash == h && keyEquals(e.key, name)) {
                value = e.value;
            }
            e = e.next;
        }
        return value;
    }

    @Override
    public final String get(CharSequence name, String defaultValue) {
        requireNonNull(defaultValue, "defaultValue");
        final String value = get(name);
        return value != null ? value : defaultValue;
    }

    @Override
    public final List<String> getAll(CharSequence name) {
        requireNonNull(name, "name");
        return getAllReversed(name).reverse();
    }

    private ImmutableList<String> getAllReversed(CharSequence name) {
        final int h = AsciiString.hashCode(name);
        final int i = index(h);
        HeaderEntry e = entries[i];

        if (e == null) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<String> builder = ImmutableList.builder();
        do {
            if (e.hash == h && keyEquals(e.key, name)) {
                builder.add(e.getValue());
            }
            e = e.next;
        } while (e != null);
        return builder.build();
    }

    @Nullable
    @Override
    public final Integer getInt(CharSequence name) {
        final String v = get(name);
        return toInteger(v);
    }

    @Override
    public final int getInt(CharSequence name, int defaultValue) {
        final Integer v = getInt(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    @Override
    public final Long getLong(CharSequence name) {
        final String v = get(name);
        return toLong(v);
    }

    @Override
    public final long getLong(CharSequence name, long defaultValue) {
        final Long v = getLong(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    @Override
    public final Float getFloat(CharSequence name) {
        final String v = get(name);
        return toFloat(v);
    }

    @Override
    public final float getFloat(CharSequence name, float defaultValue) {
        final Float v = getFloat(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    @Override
    public final Double getDouble(CharSequence name) {
        final String v = get(name);
        return toDouble(v);
    }

    @Override
    public final double getDouble(CharSequence name, double defaultValue) {
        final Double v = getDouble(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    @Override
    public final Long getTimeMillis(CharSequence name) {
        final String v = get(name);
        return toTimeMillis(v);
    }

    @Override
    public final long getTimeMillis(CharSequence name, long defaultValue) {
        final Long v = getTimeMillis(name);
        return v != null ? v : defaultValue;
    }

    @Override
    public final boolean contains(CharSequence name) {
        requireNonNull(name, "name");
        final int h = AsciiString.hashCode(name);
        final int i = index(h);
        HeaderEntry e = entries[i];
        // loop until the first header was found
        while (e != null) {
            if (e.hash == h && keyEquals(e.key, name)) {
                return true;
            }
            e = e.next;
        }
        return false;
    }

    @Override
    public final boolean contains(CharSequence name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        final int h = AsciiString.hashCode(name);
        final int i = index(h);
        HeaderEntry e = entries[i];
        while (e != null) {
            if (e.hash == h && keyEquals(e.key, name) &&
                AsciiString.contentEquals(e.value, value)) {
                return true;
            }
            e = e.next;
        }
        return false;
    }

    @Override
    public final boolean containsObject(CharSequence name, Object value) {
        requireNonNull(value, "value");
        return contains(name, fromObject(value));
    }

    @Override
    public final boolean containsInt(CharSequence name, int value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsLong(CharSequence name, long value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsFloat(CharSequence name, float value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsDouble(CharSequence name, double value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsTimeMillis(CharSequence name, long value) {
        return contains(name, fromTimeMillis(value));
    }

    @Override
    public final int size() {
        return size;
    }

    @Override
    public final boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public final Set<AsciiString> names() {
        if (isEmpty()) {
            return ImmutableSet.of();
        }
        final ImmutableSet.Builder<AsciiString> builder = ImmutableSet.builder();
        HeaderEntry e = head.after;
        while (e != head) {
            builder.add(e.getKey());
            e = e.after;
        }
        return builder.build();
    }

    @Override
    public final Iterator<Entry<AsciiString, String>> iterator() {
        return new HeaderIterator();
    }

    @Override
    public final Iterator<String> valueIterator(CharSequence name) {
        return getAll(name).iterator();
    }

    @Override
    public final void forEach(BiConsumer<AsciiString, String> action) {
        requireNonNull(action, "action");
        for (Entry<AsciiString, String> e : this) {
            action.accept(e.getKey(), e.getValue());
        }
    }

    @Override
    public final void forEachValue(CharSequence name, Consumer<String> action) {
        requireNonNull(name, "name");
        requireNonNull(action, "action");
        for (final Iterator<String> i = valueIterator(name); i.hasNext();) {
            action.accept(i.next());
        }
    }

    // Mutators

    final void endOfStream(boolean endOfStream) {
        this.endOfStream = endOfStream;
    }

    @Nullable
    final String getAndRemove(CharSequence name) {
        requireNonNull(name, "name");
        final int h = AsciiString.hashCode(name);
        return remove0(h, index(h), name);
    }

    final String getAndRemove(CharSequence name, String defaultValue) {
        requireNonNull(defaultValue, "defaultValue");
        final String value = getAndRemove(name);
        return value != null ? value : defaultValue;
    }

    final List<String> getAllAndRemove(CharSequence name) {
        final List<String> all = getAll(name);
        if (!all.isEmpty()) {
            remove(name);
        }
        return all;
    }

    @Nullable
    final Integer getIntAndRemove(CharSequence name) {
        final String v = getAndRemove(name);
        return toInteger(v);
    }

    final int getIntAndRemove(CharSequence name, int defaultValue) {
        final Integer v = getIntAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Long getLongAndRemove(CharSequence name) {
        final String v = getAndRemove(name);
        return toLong(v);
    }

    final long getLongAndRemove(CharSequence name, long defaultValue) {
        final Long v = getLongAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Float getFloatAndRemove(CharSequence name) {
        final String v = getAndRemove(name);
        return toFloat(v);
    }

    final float getFloatAndRemove(CharSequence name, float defaultValue) {
        final Float v = getFloatAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Double getDoubleAndRemove(CharSequence name) {
        final String v = getAndRemove(name);
        return toDouble(v);
    }

    final double getDoubleAndRemove(CharSequence name, double defaultValue) {
        final Double v = getDoubleAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Long getTimeMillisAndRemove(CharSequence name) {
        final String v = getAndRemove(name);
        return toTimeMillis(v);
    }

    final long getTimeMillisAndRemove(CharSequence name, long defaultValue) {
        final Long v = getTimeMillisAndRemove(name);
        return v != null ? v : defaultValue;
    }

    final void add(CharSequence name, String value) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(value, "value");
        final int h = normalizedName.hashCode();
        final int i = index(h);
        add0(h, i, normalizedName, value);
    }

    final void add(CharSequence name, Iterable<String> values) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(values, "values");
        final int h = normalizedName.hashCode();
        final int i = index(h);
        for (String v : values) {
            requireNonNullElement(values, v);
            add0(h, i, normalizedName, v);
        }
    }

    final void add(CharSequence name, String... values) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(values, "values");
        final int h = normalizedName.hashCode();
        final int i = index(h);
        for (String v : values) {
            requireNonNullElement(values, v);
            add0(h, i, normalizedName, v);
        }
    }

    final void add(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        if (headers == this) {
            throw new IllegalArgumentException("can't add to itself.");
        }

        if (!addFast(headers)) {
            addSlow(headers);
        }
    }

    final void addObject(CharSequence name, Object value) {
        requireNonNull(value, "value");
        add(name, fromObject(value));
    }

    final void addObject(CharSequence name, Iterable<?> values) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(values, "values");
        for (Object v : values) {
            requireNonNullElement(values, v);
            addObject(normalizedName, v);
        }
    }

    final void addObject(CharSequence name, Object... values) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(values, "values");
        for (Object v : values) {
            requireNonNullElement(values, v);
            addObject(normalizedName, v);
        }
    }

    void addObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        if (headers == this) {
            throw new IllegalArgumentException("can't add to itself.");
        }

        if (!addFast(headers)) {
            addObjectSlow(headers);
        }
    }

    final void addInt(CharSequence name, int value) {
        add(name, String.valueOf(value));
    }

    final void addLong(CharSequence name, long value) {
        add(name, String.valueOf(value));
    }

    final void addFloat(CharSequence name, float value) {
        add(name, String.valueOf(value));
    }

    final void addDouble(CharSequence name, double value) {
        add(name, String.valueOf(value));
    }

    final void addTimeMillis(CharSequence name, long value) {
        add(name, fromTimeMillis(value));
    }

    final void set(CharSequence name, String value) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(value, "value");
        final int h = normalizedName.hashCode();
        final int i = index(h);
        remove0(h, i, normalizedName);
        add0(h, i, normalizedName, value);
    }

    final void set(CharSequence name, Iterable<String> values) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(values, "values");

        final int h = normalizedName.hashCode();
        final int i = index(h);

        remove0(h, i, normalizedName);
        for (String v : values) {
            requireNonNullElement(values, v);
            add0(h, i, normalizedName, v);
        }
    }

    final void set(CharSequence name, String... values) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(values, "values");

        final int h = normalizedName.hashCode();
        final int i = index(h);

        remove0(h, i, normalizedName);
        for (String v : values) {
            requireNonNullElement(values, v);
            add0(h, i, normalizedName, v);
        }
    }

    final void set(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        requireNonNull(headers, "headers");
        if (headers == this) {
            return;
        }

        for (Entry<? extends CharSequence, String> e : headers) {
            remove(e.getKey());
        }

        if (!addFast(headers)) {
            addSlow(headers);
        }
    }

    public HttpHeadersBase setIfAbsent(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        requireNonNull(headers, "headers");
        final Set<AsciiString> existingNames = names();
        if (!setIfAbsentFast(headers, existingNames)) {
            setIfAbsentSlow(headers, existingNames);
        }
        return this;
    }

    private boolean setIfAbsentFast(Iterable<? extends Entry<? extends CharSequence, String>> headers,
                                    Set<AsciiString> existingNames) {

        if (!(headers instanceof HttpHeadersBase)) {
            return false;
        }

        final HttpHeadersBase headersBase = (HttpHeadersBase) headers;
        HeaderEntry e = headersBase.head.after;

        while (e != headersBase.head) {
            final AsciiString key = e.key;
            final String value = e.value;
            assert key != null;
            assert value != null;
            if (!existingNames.contains(key)) {
                add0(e.hash, index(e.hash), key, value);
            }
            e = e.after;
        }

        return true;
    }

    private void setIfAbsentSlow(Iterable<? extends Entry<? extends CharSequence, String>> headers,
                                 Set<AsciiString> existingNames) {

        for (Entry<? extends CharSequence, String> header : headers) {
            final AsciiString key = AsciiString.of(header.getKey());
            if (!existingNames.contains(key)) {
                add(key, header.getValue());
            }
        }
    }

    final void setObject(CharSequence name, Object value) {
        requireNonNull(value, "value");
        set(name, fromObject(value));
    }

    final void setObject(CharSequence name, Iterable<?> values) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(values, "values");

        final int h = normalizedName.hashCode();
        final int i = index(h);

        remove0(h, i, normalizedName);
        for (Object v: values) {
            requireNonNullElement(values, v);
            add0(h, i, normalizedName, fromObject(v));
        }
    }

    final void setObject(CharSequence name, Object... values) {
        final AsciiString normalizedName = HttpHeaderNames.of(name);
        requireNonNull(values, "values");

        final int h = normalizedName.hashCode();
        final int i = index(h);

        remove0(h, i, normalizedName);
        for (Object v: values) {
            requireNonNullElement(values, v);
            add0(h, i, normalizedName, fromObject(v));
        }
    }

    final void setObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        requireNonNull(headers, "headers");
        if (headers == this) {
            return;
        }

        for (Entry<? extends CharSequence, ?> e : headers) {
            remove(e.getKey());
        }

        if (!addFast(headers)) {
            addObjectSlow(headers);
        }
    }

    final void setInt(CharSequence name, int value) {
        set(name, String.valueOf(value));
    }

    final void setLong(CharSequence name, long value) {
        set(name, String.valueOf(value));
    }

    final void setFloat(CharSequence name, float value) {
        set(name, String.valueOf(value));
    }

    final void setDouble(CharSequence name, double value) {
        set(name, String.valueOf(value));
    }

    final void setTimeMillis(CharSequence name, long value) {
        set(name, fromTimeMillis(value));
    }

    final boolean remove(CharSequence name) {
        requireNonNull(name, "name");
        final int h = AsciiString.hashCode(name);
        return remove0(h, index(h), name) != null;
    }

    final void clear() {
        Arrays.fill(entries, null);
        firstNonPseudo = head.before = head.after = head;
        size = 0;
    }

    private static void requireNonNullElement(Object values, @Nullable Object e) {
        if (e == null) {
            throw new NullPointerException("values contains null: " + values);
        }
    }

    private int index(int hash) {
        return hash & hashMask;
    }

    private void add0(int h, int i, AsciiString name, String value) {
        validateValue(value);
        // Update the hash table.
        entries[i] = new HeaderEntry(h, name, value, entries[i]);
        ++size;
    }

    private static void validateValue(String value) {
        final int valueLength = value.length();
        for (int i = 0; i < valueLength; i++) {
            final char ch = value.charAt(i);
            if ((ch & PROHIBITED_VALUE_CHAR_MASK) != 0) { // ch >= 16
                continue;
            }

            // ch < 16
            if (PROHIBITED_VALUE_CHARS.get(ch)) {
                throw new IllegalArgumentException(malformedHeaderValueMessage(value));
            }
        }
    }

    private static String malformedHeaderValueMessage(String value) {
        final StringBuilder buf = new StringBuilder(IntMath.saturatedAdd(value.length(), 64));
        buf.append("malformed header value: ");

        final int valueLength = value.length();
        for (int i = 0; i < valueLength; i++) {
            final char ch = value.charAt(i);
            if (PROHIBITED_VALUE_CHARS.get(ch)) {
                buf.append(PROHIBITED_VALUE_CHAR_NAMES[ch]);
            } else {
                buf.append(ch);
            }
        }

        return buf.toString();
    }

    private boolean addFast(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        if (!(headers instanceof HttpHeadersBase)) {
            return false;
        }

        final HttpHeadersBase headersBase = (HttpHeadersBase) headers;
        HeaderEntry e = headersBase.head.after;
        while (e != headersBase.head) {
            final AsciiString key = e.key;
            final String value = e.value;
            assert key != null;
            assert value != null;
            add0(e.hash, index(e.hash), key, value);
            e = e.after;
        }

        return true;
    }

    private void addSlow(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        // Slow copy
        for (Entry<? extends CharSequence, String> header : headers) {
            add(header.getKey(), header.getValue());
        }
    }

    private void addObjectSlow(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        // Slow copy
        for (Entry<? extends CharSequence, ?> header : headers) {
            addObject(header.getKey(), header.getValue());
        }
    }

    /**
     * Removes all the entries whose hash code equals {@code h} and whose name is equal to {@code name}.
     *
     * @return the first value inserted, or {@code null} if there is no such header.
     */
    @Nullable
    private String remove0(int h, int i, CharSequence name) {
        HeaderEntry e = entries[i];
        if (e == null) {
            return null;
        }

        String value = null;
        HeaderEntry next = e.next;
        while (next != null) {
            if (next.hash == h && keyEquals(next.key, name)) {
                value = next.value;
                e.next = next.next;
                next.remove();
                --size;
            } else {
                e = next;
            }

            next = e.next;
        }

        e = entries[i];
        if (e.hash == h && keyEquals(e.key, name)) {
            if (value == null) {
                value = e.value;
            }
            entries[i] = e.next;
            e.remove();
            --size;
        }

        return value;
    }

    private static boolean keyEquals(@Nullable AsciiString a, CharSequence b) {
        return a != null && (a == b || a.contentEqualsIgnoreCase(b));
    }

    // Conversion functions

    @Nullable
    private static Integer toInteger(@Nullable String v) {
        try {
            return v != null ? Integer.parseInt(v) : null;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @Nullable
    private static Long toLong(@Nullable String v) {
        try {
            return v != null ? Long.parseLong(v) : null;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @Nullable
    private static Float toFloat(@Nullable String v) {
        try {
            return v != null ? Float.parseFloat(v) : null;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @Nullable
    private static Double toDouble(@Nullable String v) {
        try {
            return v != null ? Double.parseDouble(v) : null;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @Nullable
    private static Long toTimeMillis(@Nullable String v) {
        if (v == null) {
            return null;
        }

        try {
            @SuppressWarnings("UseOfObsoleteDateTimeApi")
            final Date date = DateFormatter.parseHttpDate(v);
            return date != null ? date.getTime() : null;
        } catch (Exception ignore) {
            // `parseHttpDate()` can raise an exception rather than returning `null`
            // when the given value has more than 64 characters.
            return null;
        }
    }

    private static String fromTimeMillis(long value) {
        return StringValueConverter.INSTANCE.convertTimeMillis(value);
    }

    private static String fromObject(Object value) {
        final String strVal = StringValueConverter.INSTANCE.convertObject(value);
        assert strVal != null : value + " converted to null.";
        return strVal;
    }

    // hashCode(), equals() and toString()

    @Override
    public final int hashCode() {
        int result = HASH_CODE_SEED;
        for (AsciiString name : names()) {
            result = (result * 31 + name.hashCode()) * 31 + getAll(name).hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof HttpHeaderGetters)) {
            return false;
        }

        final HttpHeaderGetters that = (HttpHeaderGetters) o;
        if (isEndOfStream() != that.isEndOfStream() ||
            size() != that.size()) {
            return false;
        }

        if (that instanceof HttpHeadersBase) {
            return equalsFast((HttpHeadersBase) that);
        } else {
            return equalsSlow(that);
        }
    }

    private boolean equalsFast(HttpHeadersBase that) {
        HeaderEntry e = head.after;
        while (e != head) {
            final AsciiString name = e.getKey();
            if (!getAllReversed(name).equals(that.getAllReversed(name))) {
                return false;
            }
            e = e.after;
        }
        return true;
    }

    private boolean equalsSlow(HttpHeaderGetters that) {
        HeaderEntry e = head.after;
        while (e != head) {
            final AsciiString name = e.getKey();
            if (!Iterators.elementsEqual(valueIterator(name), that.valueIterator(name))) {
                return false;
            }
            e = e.after;
        }
        return true;
    }

    @Override
    public String toString() {
        if (size == 0) {
            return endOfStream ? "[EOS]" : "[]";
        }

        final StringBuilder sb = new StringBuilder(7 + size * 20);
        if (endOfStream) {
            sb.append("[EOS, ");
        } else {
            sb.append('[');
        }

        HeaderEntry e = head.after;
        while (e != head) {
            sb.append(e.key).append('=').append(e.value).append(", ");
            e = e.after;
        }

        final int length = sb.length();
        sb.setCharAt(length - 2, ']');
        return sb.substring(0, length - 1);
    }

    // Iterator implementations

    private final class HeaderIterator implements Iterator<Map.Entry<AsciiString, String>> {
        private HeaderEntry current = head;

        @Override
        public boolean hasNext() {
            return current.after != head;
        }

        @Override
        public Entry<AsciiString, String> next() {
            current = current.after;

            if (current == head) {
                throw new NoSuchElementException();
            }

            return current;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("read-only");
        }
    }

    private final class HeaderEntry implements Map.Entry<AsciiString, String> {

        final int hash;
        @Nullable
        final AsciiString key;
        @Nullable
        final String value;
        /**
         * In bucket linked list.
         */
        @Nullable
        HeaderEntry next;
        /**
         * Overall insertion order linked list.
         */
        HeaderEntry before;
        HeaderEntry after;

        /**
         * Creates a new head node.
         */
        HeaderEntry() {
            hash = -1;
            key = null;
            value = null;
            before = after = this;
        }

        HeaderEntry(int hash, AsciiString key, String value, HeaderEntry next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;

            // Make sure the pseudo headers fields are first in iteration order
            if (hasPseudoHeaderFormat(key)) {
                after = firstNonPseudo;
                before = firstNonPseudo.before;
            } else {
                after = head;
                before = head.before;
                if (firstNonPseudo == head) {
                    firstNonPseudo = this;
                }
            }
            pointNeighborsToThis();
        }

        void pointNeighborsToThis() {
            before.after = this;
            after.before = this;
        }

        void remove() {
            if (this == firstNonPseudo) {
                firstNonPseudo = firstNonPseudo.after;
            }

            before.after = after;
            after.before = before;
        }

        @Override
        public AsciiString getKey() {
            assert key != null;
            return key;
        }

        @Override
        public String getValue() {
            assert value != null;
            return value;
        }

        @Override
        public String setValue(String value) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^ AsciiString.hashCode(value);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof Map.Entry)) {
                return false;
            }

            final Map.Entry<?, ?> that = (Map.Entry<?, ?>) o;
            final Object thatKey = that.getKey();
            return thatKey instanceof AsciiString &&
                   keyEquals(key, (CharSequence) thatKey) &&
                   Objects.equals(value, that.getValue());
        }

        @Override
        public String toString() {
            if (key == null) {
                return "<HEAD>";
            }

            assert value != null;
            return new StringBuilder(key.length() + value.length() + 1)
                    .append(key)
                    .append('=')
                    .append(value)
                    .toString();
        }
    }
}
