/*
 * Copyright 2022 LINE Corporation
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

import static com.linecorp.armeria.common.ImmutableAttributesBuilder.NULL_VALUE;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

final class ImmutableAttributes implements Attributes {

    static final Attributes EMPTY = new ImmutableAttributes(null, ImmutableMap.of());

    private final @Nullable AttributesGetters parent;
    private final Map<AttributeKey<?>, Object> attributes;

    ImmutableAttributes(@Nullable AttributesGetters parent,
                        Map<AttributeKey<?>, Object> attributes) {
        this.parent = parent;
        this.attributes = ImmutableMap.copyOf(attributes);
    }

    @Override
    public AttributesBuilder toBuilder() {
        final ImmutableAttributesBuilder builder = new ImmutableAttributesBuilder(parent);
        //noinspection unchecked
        attributes.forEach((k, v) -> builder.set((AttributeKey<Object>) k, v));
        return builder;
    }

    @Override
    public ConcurrentAttributes toConcurrentAttributes() {
        final ConcurrentAttributes concurrentAttributes;
        if (parent == null) {
            concurrentAttributes = ConcurrentAttributes.of();
        } else {
            concurrentAttributes = ConcurrentAttributes.fromParent(parent);
        }

        if (!attributes.isEmpty()) {
            attributes.forEach((k, v) -> {
                if (v == NULL_VALUE) {
                    // NULL_VALUE is only valid for ImmutableAttributes
                    v = null;
                }
                //noinspection unchecked
                concurrentAttributes.set((AttributeKey<Object>) k, v);
            });
        }

        return concurrentAttributes;
    }

    @Nullable
    @Override
    public <T> T attr(AttributeKey<T> key) {
        requireNonNull(key, "key");
        final T value = ownAttr0(key);
        if (value != null) {
            return value == NULL_VALUE ? null : value;
        }

        if (parent == null) {
            return null;
        }
        return parent.attr(key);
    }

    @Nullable
    @Override
    public <T> T ownAttr(AttributeKey<T> key) {
        final T value = ownAttr0(key);
        return value == NULL_VALUE ? null : value;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private <T> T ownAttr0(AttributeKey<T> key) {
        return (T) attributes.get(key);
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        if (parent == null) {
            return ownAttrs();
        }
        return new ConcatenatedIterator(parent.attrs(), ownAttrs());
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> ownAttrs() {
        if (attributes.isEmpty()) {
            return Collections.emptyIterator();
        }
        return attributes.entrySet().stream().filter(x -> x.getValue() != NULL_VALUE).iterator();
    }

    @Nullable
    @Override
    public AttributesGetters parent() {
        return parent;
    }

    @Override
    public boolean isEmpty() {
        return attributes.isEmpty() && (parent == null || parent.isEmpty());
    }

    @Override
    public int size() {
        if (parent == null) {
            return attributes.size();
        } else {
            // May have duplicate keys
            return Iterators.size(attrs());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof AttributesGetters)) {
            return false;
        }

        final AttributesGetters that = (AttributesGetters) o;
        if (size() != that.size()) {
            return false;
        }

        for (final Iterator<Entry<AttributeKey<?>, Object>> it = attrs(); it.hasNext();) {
            final Entry<AttributeKey<?>, Object> next = it.next();
            final Object thisVal = next.getValue();
            final Object thatVal = that.attr(next.getKey());
            if (!thisVal.equals(thatVal)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hashCode = 0;
        for (final Iterator<Entry<AttributeKey<?>, Object>> it = attrs(); it.hasNext();) {
            final Entry<AttributeKey<?>, Object> next = it.next();
            hashCode += next.getKey().hashCode();
            hashCode += next.getValue().hashCode();
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return Iterators.toString(attrs());
    }

    private class ConcatenatedIterator implements Iterator<Entry<AttributeKey<?>, Object>> {

        private final Iterator<Entry<AttributeKey<?>, Object>> parentIt;
        private final Iterator<Entry<AttributeKey<?>, Object>> childIt;

        // Need to prefetch next to check whether the attribute in root whose key is the same to the attribute
        // in the child so that this iterator does not yield that attribute.
        @Nullable
        private Entry<AttributeKey<?>, Object> next;

        ConcatenatedIterator(Iterator<Entry<AttributeKey<?>, Object>> parentIt,
                             Iterator<Entry<AttributeKey<?>, Object>> childIt) {
            this.childIt = childIt;
            this.parentIt = parentIt;

            if (childIt.hasNext()) {
                next = childIt.next();
            } else if (parentIt.hasNext()) {
                next = parentIt.next();
            } else {
                next = null;
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Entry<AttributeKey<?>, Object> next() {
            final Entry<AttributeKey<?>, Object> next = this.next;
            if (next == null) {
                throw new NoSuchElementException();
            }

            if (childIt.hasNext()) {
                this.next = childIt.next();
            } else {
                // Skip the attribute in parentIt if it's in the child.
                for (;;) {
                    if (parentIt.hasNext()) {
                        final Entry<AttributeKey<?>, Object> tempNext = parentIt.next();
                        // The value shaded by NULL_VALUE is also skipped.
                        if (ownAttr0(tempNext.getKey()) == null) {
                            this.next = tempNext;
                            break;
                        }
                    } else {
                        this.next = null;
                        break;
                    }
                }
            }
            return next;
        }
    }
}
