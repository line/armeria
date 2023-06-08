/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.convertHeaderValue;

import java.util.AbstractMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.RequestHeadersBuilder;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;

/**
 * Custom implementation of {@link HttpHeaders} that delegates to {@link RequestHeadersBuilder}.
 */
public final class ArmeriaHttpHeaders extends HttpHeaders {

    private final RequestHeadersBuilder builder;
//    private final CaseInsensitiveMap connectionDisallowedList;

    /**
     * Creates a new instance.
     *
     * @param builder the builder to delegate to
     */
    public ArmeriaHttpHeaders(RequestHeadersBuilder builder) {
        this.builder = builder;
//        connectionDisallowedList = toLowercaseMap(valueCharSequenceIterator(HttpHeaderNames.CONNECTION), 8);
    }

    /**
     * Creates a new instance.
     *
     * @param builder the builder to delegate to
     * @param headers the headers to copy
     */
    public ArmeriaHttpHeaders(RequestHeadersBuilder builder, HttpHeaders headers) {
        this.builder = builder;
        headers.forEach(e -> this.add(e.getKey(), e.getValue()));
//        connectionDisallowedList = toLowercaseMap(valueCharSequenceIterator(HttpHeaderNames.CONNECTION), 8);
    }

    @Override
    public String get(String name) {
        return builder.get(name);
    }

    @Override
    public Integer getInt(CharSequence name) {
        return builder.getInt(name);
    }

    @Override
    public int getInt(CharSequence name, int defaultValue) {
        return builder.getInt(name, defaultValue);
    }

    @Override
    public Short getShort(CharSequence name) {
        return builder.getInt(name).shortValue();
    }

    @Override
    public short getShort(CharSequence name, short defaultValue) {
        return (short) builder.getInt(name, defaultValue);
    }

    @Override
    public Long getTimeMillis(CharSequence name) {
        return builder.getTimeMillis(name);
    }

    @Override
    public long getTimeMillis(CharSequence name, long defaultValue) {
        return builder.getTimeMillis(name, defaultValue);
    }

    @Override
    public List<String> getAll(String name) {
        return builder.getAll(name);
    }

    @Override
    public List<Entry<String, String>> entries() {
        return builder.stream()
                      .map(e -> new AbstractMap.SimpleEntry<>(e.getKey().toString(), e.getValue()))
                      .collect(Collectors.toList());
    }

    @Override
    public boolean contains(String name) {
        return builder.contains(name);
    }

    @Override
    public Iterator<Entry<String, String>> iterator() {
        return builder.stream()
                      .map(e -> (Entry<String, String>) new AbstractMap.SimpleEntry<>(
                              e.getKey().toString(), e.getValue())
                      )
                      .iterator();
    }

    @Override
    public Iterator<Entry<CharSequence, CharSequence>> iteratorCharSequence() {
        return  builder.stream()
                       .map(e -> (Entry<CharSequence, CharSequence>) new AbstractMap.SimpleEntry<>(
                               (CharSequence) e.getKey().toString(),(CharSequence) e.getValue())
                       )
                       .iterator();
    }

    @Override
    public boolean isEmpty() {
        return builder.isEmpty();
    }

    @Override
    public int size() {
        return builder.size();
    }

    @Override
    public Set<String> names() {
        return builder.names().stream().map(AsciiString::toString).collect(Collectors.toSet());
    }

    @Override
    public HttpHeaders add(String name, Object value) {
        final AsciiString asciiName = AsciiString.of(name);
        final CharSequence charSequenceValue = (CharSequence) value;

        builder.add(asciiName, convertHeaderValue(asciiName, charSequenceValue));

        return this;
    }

    @Override
    public HttpHeaders add(String name, Iterable<?> values) {
        builder.addObject(name, values);
        return this;
    }

    @Override
    public HttpHeaders addInt(CharSequence name, int value) {
        builder.addInt(name, value);
        return this;
    }

    @Override
    public HttpHeaders addShort(CharSequence name, short value) {
        builder.addInt(name, value);
        return this;
    }

    @Override
    public HttpHeaders set(String name, Object value) {
        builder.setObject(name, value);
        return this;
    }

    @Override
    public HttpHeaders set(String name, Iterable<?> values) {
        builder.setObject(name, values);
        return this;
    }

    @Override
    public HttpHeaders setInt(CharSequence name, int value) {
        builder.setInt(name, value);
        return this;
    }

    @Override
    public HttpHeaders setShort(CharSequence name, short value) {
        builder.setInt(name, value);
        return this;
    }

    @Override
    public HttpHeaders remove(String name) {
        builder.remove(name);
        return this;
    }

    @Override
    public HttpHeaders clear() {
        builder.clear();
        return this;
    }
}
