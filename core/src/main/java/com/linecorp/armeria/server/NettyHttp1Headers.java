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

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.handler.codec.CharSequenceValueConverter;
import io.netty.handler.codec.DefaultHeaders.NameValidator;
import io.netty.handler.codec.DefaultHeaders.ValueValidator;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AsciiString;

final class NettyHttp1Headers extends HttpHeaders {

    private static final NameValidator<CharSequence> NAME_VALIDATOR =
            DefaultHttpHeadersFactory.headersFactory().getNameValidator();
    private static final ValueValidator<CharSequence> VALUE_VALIDATOR =
            DefaultHttpHeadersFactory.headersFactory().getValueValidator();

    private final RequestHeadersBuilder delegate;

    NettyHttp1Headers() {
        delegate = RequestHeaders.builder();
    }

    @Override
    @Nullable
    public String get(String name) {
        return delegate.get(name);
    }

    @Override
    @Nullable
    public Integer getInt(CharSequence name) {
        return delegate.getInt(name);
    }

    @Override
    public int getInt(CharSequence name, int defaultValue) {
        return delegate.getInt(name, defaultValue);
    }

    @Override
    @Nullable
    public Short getShort(CharSequence name) {
        final Integer intValue = delegate.getInt(name);
        if (intValue == null || intValue < Short.MIN_VALUE || intValue > Short.MAX_VALUE) {
            return null;
        }
        return intValue.shortValue();
    }

    @Override
    public short getShort(CharSequence name, short defaultValue) {
        final Integer intValue = delegate.getInt(name);
        if (intValue == null || intValue < Short.MIN_VALUE || intValue > Short.MAX_VALUE) {
            return defaultValue;
        }
        return intValue.shortValue();
    }

    @Override
    @Nullable
    public Long getTimeMillis(CharSequence name) {
        return delegate.getTimeMillis(name);
    }

    @Override
    public long getTimeMillis(CharSequence name, long defaultValue) {
        return delegate.getTimeMillis(name, defaultValue);
    }

    @Override
    public List<String> getAll(String name) {
        return delegate.getAll(name);
    }

    @Override
    public List<Entry<String, String>> entries() {
        return delegate.stream()
                       .map(e -> Maps.immutableEntry(e.getKey().toString(), e.getValue()))
                       .collect(Collectors.toList());
    }

    @Override
    public boolean contains(String name) {
        return delegate.contains(name);
    }

    @Override
    public Iterator<Entry<String, String>> iterator() {
        return delegate.stream().map(e -> Maps.immutableEntry(e.getKey().toString(), e.getValue()))
                       .iterator();
    }

    @Override
    public Iterator<Entry<CharSequence, CharSequence>> iteratorCharSequence() {
        return delegate.stream()
                       .map(e -> Maps.<CharSequence, CharSequence>immutableEntry(e.getKey().toString(),
                                                                                e.getValue()))

                       .iterator();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public Set<String> names() {
        return delegate.names().stream().map(AsciiString::toString).collect(Collectors.toSet());
    }

    @Override
    public HttpHeaders add(String name, Object value) {
        final String strValue = validateValue(value);
        delegate.add(validatedName(name), strValue);
        return this;
    }

    @Override
    public HttpHeaders add(String name, Iterable<?> values) {
        delegate.add(validatedName(name), validateValues(values));
        return this;
    }

    @Override
    public HttpHeaders addInt(CharSequence name, int value) {
        delegate.addInt(validatedName(name), value);
        return this;
    }

    @Override
    public HttpHeaders addShort(CharSequence name, short value) {
        delegate.addInt(validatedName(name), value);
        return this;
    }

    @Override
    public HttpHeaders set(String name, Object value) {
        delegate.set(validatedName(name), validateValue(value));
        return this;
    }

    @Override
    public HttpHeaders set(String name, Iterable<?> values) {
        final List<String> strValues = validateValues(values);
        delegate.set(validatedName(name), strValues);
        return this;
    }

    @Override
    public HttpHeaders setInt(CharSequence name, int value) {
        delegate.setInt(validatedName(name), value);
        return this;
    }

    @Override
    public HttpHeaders setShort(CharSequence name, short value) {
        delegate.setInt(validatedName(name), value);
        return this;
    }

    @Override
    public HttpHeaders remove(String name) {
        delegate.remove(name);
        return this;
    }

    @Override
    public HttpHeaders clear() {
        delegate.clear();
        return this;
    }

    private static CharSequence validatedName(CharSequence name) {
        NAME_VALIDATOR.validateName(name);
        return name;
    }

    private static List<String> validateValues(Iterable<?> values) {
        return ImmutableList.copyOf(values).stream()
                            .map(NettyHttp1Headers::validateValue)
                            .collect(Collectors.toList());
    }

    private static String validateValue(Object value) {
        final CharSequence strValue = CharSequenceValueConverter.INSTANCE.convertObject(value);
        VALUE_VALIDATOR.validate(strValue);
        return strValue.toString();
    }

    RequestHeadersBuilder delegate() {
        return delegate;
    }
}
