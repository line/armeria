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

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import io.netty.util.AttributeKey;

final class DefaultAttributeMap {

    // Forked from Netty 4.1.34 at 506f0d8f8c10e1b24924f7d992a726d7bdd2e486

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultAttributeMap, AtomicReferenceArray>
            updater = AtomicReferenceFieldUpdater.newUpdater(DefaultAttributeMap.class,
                                                             AtomicReferenceArray.class, "attributes");

    private static final int BUCKET_SIZE = 8;
    private static final int MASK = BUCKET_SIZE - 1;

    // Not using ConcurrentHashMap due to high memory consumption.
    // Initialize lazily to reduce memory consumption; updated by AtomicReferenceFieldUpdater above.
    @VisibleForTesting
    @Nullable
    volatile AtomicReferenceArray<DefaultAttribute<?>> attributes;

    @Nullable
    private final RequestContext rootAttributeMap;

    DefaultAttributeMap(@Nullable RequestContext rootAttributeMap) {
        this.rootAttributeMap = rootAttributeMap;
    }

    @Nullable
    <T> T ownAttr(AttributeKey<T> key) {
        return attr(key, true);
    }

    @Nullable
    <T> T attr(AttributeKey<T> key) {
        return attr(key, false);
    }

    @Nullable
    private <T> T attr(AttributeKey<T> key, boolean ownAttr) {
        requireNonNull(key, "key");
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            if (!ownAttr && rootAttributeMap != null) {
                return rootAttributeMap.attr(key);
            }
            return null;
        }

        final int i = index(key);
        final DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {
            if (!ownAttr && rootAttributeMap != null) {
                return rootAttributeMap.attr(key);
            }
            return null;
        }

        synchronized (head) {
            DefaultAttribute<?> curr = head;
            for (;;) {
                final DefaultAttribute<?> next = curr.next;
                if (next == null) {
                    if (!ownAttr && rootAttributeMap != null) {
                        return rootAttributeMap.attr(key);
                    }
                    return null;
                }

                if (next.key == key) {
                    @SuppressWarnings("unchecked")
                    final T result = (T) next.getValue();
                    return result;
                }
                curr = next;
            }
        }
    }

    private static int index(AttributeKey<?> key) {
        return key.id() & MASK;
    }

    @Nullable
    <T> T setAttrIfAbsent(AttributeKey<T> key, @Nullable T value) {
        final DefaultAttribute<T> result = setAttr(key, value, true);
        if (result.getValue() != value) {
            return result.getValue();
        }
        return null;
    }

    <T> void setAttr(AttributeKey<T> key, @Nullable T value) {
        setAttr(key, value, false);
    }

    private <T> DefaultAttribute<T> setAttr(AttributeKey<T> key, @Nullable T value, boolean setIfAbsent) {
        requireNonNull(key, "key");
        AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            attributes = new AtomicReferenceArray<>(BUCKET_SIZE);

            if (!updater.compareAndSet(this, null, attributes)) {
                attributes = this.attributes;
            }
        }
        assert attributes != null;

        final int i = index(key);
        DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {

            // No head exists yet which means we may be able to add the attribute without synchronization and
            // just use compare and set. At worst, we need to fallback to synchronization and waste two
            // allocations.
            head = new DefaultAttribute<>();
            final DefaultAttribute<T> attr = new DefaultAttribute<>(key, value);
            head.next = attr;
            if (attributes.compareAndSet(i, null, head)) {
                // We were able to add it so finish the job.
                return attr;
            }

            head = attributes.get(i);
        }

        synchronized (head) {
            DefaultAttribute<?> curr = head;
            for (;;) {
                final DefaultAttribute<?> next = curr.next;
                if (next != null && next.key == key) {
                    @SuppressWarnings("unchecked")
                    final DefaultAttribute<T> attr = (DefaultAttribute<T>) next;
                    if (setIfAbsent && next.getValue() != null) {
                        return attr;
                    }

                    attr.setValue(value);
                    return attr;
                }

                if (next == null) {
                    final DefaultAttribute<T> attr = new DefaultAttribute<>(key, value);
                    curr.next = attr;
                    return attr;
                }

                curr = next;
            }
        }
    }

    Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            if (rootAttributeMap == null) {
                return Collections.emptyIterator();
            }

            final Iterator<Entry<AttributeKey<?>, Object>> rootAttrs = rootAttributeMap.attrs();
            if (!rootAttrs.hasNext()) {
                return Collections.emptyIterator();
            }

            return new CopyOnWriteAttributeIterator(rootAttrs);
        }

        final Iterator<Entry<AttributeKey<?>, Object>> ownAttrsIt = new IteratorImpl(attributes);
        if (rootAttributeMap == null) {
            return ownAttrsIt;
        }

        final Iterator<Entry<AttributeKey<?>, Object>> rootAttrs = rootAttributeMap.attrs();
        if (!rootAttrs.hasNext()) {
            return ownAttrsIt;
        }

        return new ConcatenatedIteratorImpl(ownAttrsIt, rootAttrs);
    }

    Iterator<Entry<AttributeKey<?>, Object>> ownAttrs() {
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            return Collections.emptyIterator();
        }
        return new IteratorImpl(attributes);
    }

    @VisibleForTesting
    static final class DefaultAttribute<T> implements Entry<AttributeKey<T>, T> {

        @Nullable
        private final AttributeKey<T> key;

        @Nullable
        private volatile T value;

        @Nullable
        private DefaultAttribute<?> next;

        DefaultAttribute(AttributeKey<T> key, T value) {
            this.key = key;
            this.value = value;
        }

        // The special constructor for the head of the linked-list.
        DefaultAttribute() {
            key = null;
            value = null;
        }

        @Override
        public AttributeKey<T> getKey() {
            assert key != null;
            return key;
        }

        @Nullable
        @Override
        public T getValue() {
            return value;
        }

        @Nullable
        @Override
        public T setValue(@Nullable T value) {
            final T old = this.value;
            this.value = value;
            return old;
        }
    }

    private static final class IteratorImpl implements Iterator<Entry<AttributeKey<?>, Object>> {

        private final AtomicReferenceArray<DefaultAttribute<?>> attributes;

        private int idx = -1;
        @Nullable
        private DefaultAttribute<?> next;

        IteratorImpl(AtomicReferenceArray<DefaultAttribute<?>> attributes) {
            this.attributes = attributes;
            next = findNext(null);
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Entry<AttributeKey<?>, Object> next() {
            final DefaultAttribute<?> next = this.next;
            if (next == null) {
                throw new NoSuchElementException();
            }
            this.next = findNext(next.next);
            return convert(next);
        }

        @Nullable
        private DefaultAttribute<?> findNext(@Nullable DefaultAttribute<?> next) {
            loop: for (;;) {
                if (next == null) {
                    for (idx++; idx < attributes.length(); idx++) {
                        final DefaultAttribute<?> head = attributes.get(idx);
                        if (head != null && head.next != null) {
                            next = head.next;
                            break;
                        }
                    }

                    if (next == null) {
                        return null;
                    }
                }

                while (next.getValue() == null) {
                    if ((next = next.next) == null) {
                        continue loop;
                    }
                }

                return next;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(Object o) {
        return (T) o;
    }

    private class CopyOnWriteAttributeIterator implements Iterator<Entry<AttributeKey<?>, Object>> {
        private final Iterator<Entry<AttributeKey<?>, Object>> rootAttrs;

        CopyOnWriteAttributeIterator(Iterator<Entry<AttributeKey<?>, Object>> rootAttrs) {
            this.rootAttrs = rootAttrs;
        }

        @Override
        public boolean hasNext() {
            return rootAttrs.hasNext();
        }

        @SuppressWarnings("unchecked")
        @Override
        public Entry<AttributeKey<?>, Object> next() {
            return new CopyOnWriteAttribute(rootAttrs.next());
        }
    }

    private final class ConcatenatedIteratorImpl implements Iterator<Entry<AttributeKey<?>, Object>> {

        private final Iterator<Entry<AttributeKey<?>, Object>> childIt;
        private final Iterator<Entry<AttributeKey<?>, Object>> rootIt;

        // Need to prefetch next to check whether the attribute in root whose key is the same to the attribute
        // in the child so that this iterator does not yield that attribute.
        @Nullable
        private Entry<AttributeKey<?>, Object> next;

        ConcatenatedIteratorImpl(Iterator<Entry<AttributeKey<?>, Object>> childIt,
                                 Iterator<Entry<AttributeKey<?>, Object>> rootIt) {
            this.childIt = childIt;
            this.rootIt = rootIt;

            if (childIt.hasNext()) {
                next = childIt.next();
            } else if (rootIt.hasNext()) {
                next = convert(new CopyOnWriteAttribute<>(convert(rootIt.next())));
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
                // Skip the attribute in rootIt if it's in the child.
                for (;;) {
                    if (rootIt.hasNext()) {
                        final Entry<AttributeKey<?>, Object> tempNext = rootIt.next();
                        if (ownAttr(tempNext.getKey()) == null) {
                            this.next = convert(new CopyOnWriteAttribute<>(convert(tempNext)));
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

    private final class CopyOnWriteAttribute<T> implements Entry<AttributeKey<T>, T> {
        private final Entry<AttributeKey<T>, T> rootAttr;

        @Nullable
        private volatile Entry<AttributeKey<T>, T> childAttr;

        CopyOnWriteAttribute(Entry<AttributeKey<T>, T> rootAttr) {
            this.rootAttr = rootAttr;
        }

        @Override
        public AttributeKey<T> getKey() {
            return rootAttr.getKey();
        }

        @Override
        public T getValue() {
            final Entry<AttributeKey<T>, T> childAttr = this.childAttr;
            if (childAttr != null) {
                return childAttr.getValue();
            }
            return rootAttr.getValue();
        }

        @Override
        public T setValue(T value) {
            final Entry<AttributeKey<T>, T> childAttr = this.childAttr;
            if (childAttr == null) {
                this.childAttr = setAttr(rootAttr.getKey(), value, false);
                return rootAttr.getValue();
            }

            final T old = childAttr.getValue();
            childAttr.setValue(value);
            return old;
        }
    }
}
