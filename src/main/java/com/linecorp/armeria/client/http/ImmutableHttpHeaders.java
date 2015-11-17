/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.http;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import io.netty.handler.codec.http.HttpHeaders;

/**
 * A container for HTTP headers that cannot be mutated. Just delegates read
 * operations to an underlying {@link HttpHeaders} object.
 */
final class ImmutableHttpHeaders extends HttpHeaders {

    private final HttpHeaders delegate;

    ImmutableHttpHeaders(HttpHeaders delegate) {
        this.delegate = delegate;
    }

    @Override
    public String get(String name) {
        return delegate.getAsString(name);
    }

    @Override
    public Integer getInt(CharSequence name) {
        return delegate.getInt(name);
    }

    @Override
    public int getInt(CharSequence name, int defaultValue) {
        return delegate.getInt(name, defaultValue);
    }

    @Override
    public Short getShort(CharSequence name) {
        return delegate.getShort(name);
    }

    @Override
    public short getShort(CharSequence name, short defaultValue) {
        return delegate.getShort(name, defaultValue);
    }

    @Override
    public Long getTimeMillis(CharSequence name) {
        return delegate.getTimeMillis(name);
    }

    @Override
    public long getTimeMillis(CharSequence name, long defaultValue) {
        return delegate.getTimeMillis(name, defaultValue);
    }

    @Override
    @Deprecated
    public List<String> getAll(String name) {
        return delegate.getAllAsString(name);
    }

    @Override
    @Deprecated
    public List<Entry<String, String>> entries() {
        return delegate.entries();
    }

    @Override
    @Deprecated
    public boolean contains(String name) {
        return delegate.contains(name);
    }

    @Override
    @Deprecated
    public Iterator<Entry<String, String>> iterator() {
        return delegate.iterator();
    }

    @Override
    public Iterator<Entry<CharSequence, CharSequence>> iteratorCharSequence() {
        return delegate.iteratorCharSequence();
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
        return delegate.names();
    }

    @Override
    public HttpHeaders add(String name, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders add(String name, Iterable<?> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders addInt(CharSequence name, int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders addShort(CharSequence name, short value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders set(String name, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders set(String name, Iterable<?> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders setInt(CharSequence name, int value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders setShort(CharSequence name, short value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders remove(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HttpHeaders clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object other) {
        return delegate.equals(other);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
