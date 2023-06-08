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
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.Iterators;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.netty.util.AttributeKey;

final class DefaultConcurrentAttributes implements ConcurrentAttributes {

    // Forked from Netty 4.1.34 at 506f0d8f8c10e1b24924f7d992a726d7bdd2e486
    // - Add rootAttributeMap and related methods to retrieve values from the rootAttributeMap.
    // - Add setAttrIfAbsent and computeAttrIfAbsent

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultConcurrentAttributes, AtomicReferenceArray>
            updater = AtomicReferenceFieldUpdater.newUpdater(DefaultConcurrentAttributes.class,
                                                             AtomicReferenceArray.class, "attributes");

    private static final int BUCKET_SIZE = 8;
    private static final int MASK = BUCKET_SIZE - 1;

    // Not using ConcurrentHashMap due to high memory consumption.
    // Initialize lazily to reduce memory consumption; updated by AtomicReferenceFieldUpdater above.
    @VisibleForTesting
    @Nullable
    volatile AtomicReferenceArray<DefaultAttribute<?>> attributes;

    @Nullable
    private final AttributesGetters parent;

    DefaultConcurrentAttributes(@Nullable AttributesGetters parent) {
        this.parent = parent;
    }

    @Override
    @Nullable
    public <T> T ownAttr(AttributeKey<T> key) {
        return attr(key, true);
    }

    @Override
    @Nullable
    public <T> T attr(AttributeKey<T> key) {
        return attr(key, false);
    }

    @Nullable
    private <T> T attr(AttributeKey<T> key, boolean ownAttr) {
        requireNonNull(key, "key");
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            if (!ownAttr && parent != null) {
                return parent.attr(key);
            }
            return null;
        }

        final int i = index(key);
        final DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {
            if (!ownAttr && parent != null) {
                return parent.attr(key);
            }
            return null;
        }

        head.lock();
        try {
            DefaultAttribute<?> curr = head;
            for (;;) {
                final DefaultAttribute<?> next = curr.next;
                if (next == null) {
                    if (!ownAttr && parent != null) {
                        return parent.attr(key);
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
        } finally {
            head.unlock();
        }
    }

    private static int index(AttributeKey<?> key) {
        return key.id() & MASK;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T> T getAndSet(AttributeKey<T> key, @Nullable T value) {
        requireNonNull(key, "key");
        return (T) setAttr(key, value, SetAttrMode.OLD_VALUE);
    }

    @Override
    public <T> ConcurrentAttributes set(AttributeKey<T> key, @Nullable T value) {
        requireNonNull(key, "key");
        setAttr(key, value, SetAttrMode.CUR_ATTR);
        return this;
    }

    @Nullable
    private <T> Object setAttr(AttributeKey<T> key, @Nullable T value, SetAttrMode mode) {
        requireNonNull(key, "key");
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = attributes();

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
                return getSetAttrResultForNewAttr(mode, attr);
            }

            head = attributes.get(i);
        }

        head.lock();
        try {
            DefaultAttribute<?> curr = head;
            for (;;) {
                final DefaultAttribute<?> next = curr.next;
                if (next != null && next.key == key) {
                    @SuppressWarnings("unchecked")
                    final DefaultAttribute<T> attr = (DefaultAttribute<T>) next;
                    final T oldValue = attr.setValue(value);
                    return getSetAttrResultForExistingAttr(mode, attr, oldValue);
                }

                if (next == null) {
                    final DefaultAttribute<T> attr = new DefaultAttribute<>(key, value);
                    curr.next = attr;
                    return getSetAttrResultForNewAttr(mode, attr);
                }

                curr = next;
            }
        } finally {
            head.unlock();
        }
    }

    @Nullable
    private <T> Object getSetAttrResultForNewAttr(SetAttrMode mode, DefaultAttribute<T> attr) {
        switch (mode) {
            case OLD_VALUE:
                return parent != null ? parent.ownAttr(attr.getKey()) : null;
            case CUR_ATTR:
                return attr;
            default:
                // Never reaches here.
                throw new Error();
        }
    }

    @Nullable
    private static <T> Object getSetAttrResultForExistingAttr(SetAttrMode mode, DefaultAttribute<T> attr,
                                                              @Nullable T oldValue) {
        switch (mode) {
            case OLD_VALUE:
                return oldValue;
            case CUR_ATTR:
                return attr;
            default:
                // Never reaches here.
                throw new Error();
        }
    }

    @Override
    public <T> boolean remove(AttributeKey<T> key) {
        requireNonNull(key, "key");
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = attributes();

        final int i = index(key);
        final DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {
            return false;
        }

        head.lock();
        try {
            DefaultAttribute<?> curr = head;
            for (;;) {
                final DefaultAttribute<?> next = curr.next;
                if (next == null) {
                    return false;
                }
                if (next.key == key) {
                    // Remove `next` from the chaining.
                    curr.next = next.next;
                    return true;
                }
                curr = next;
            }
        } finally {
            head.unlock();
        }
    }

    private AtomicReferenceArray<DefaultAttribute<?>> attributes() {
        AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            attributes = new AtomicReferenceArray<>(BUCKET_SIZE);

            if (!updater.compareAndSet(this, null, attributes)) {
                attributes = this.attributes;
            }
        }
        assert attributes != null;
        return attributes;
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> attrs() {
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            if (parent == null) {
                return Collections.emptyIterator();
            }

            final Iterator<Entry<AttributeKey<?>, Object>> rootAttrs = parent.attrs();
            if (!rootAttrs.hasNext()) {
                return Collections.emptyIterator();
            }

            return new CopyOnWriteIterator(rootAttrs);
        }

        final Iterator<Entry<AttributeKey<?>, Object>> ownAttrsIt = new OwnIterator(attributes);
        if (parent == null) {
            return ownAttrsIt;
        }

        final Iterator<Entry<AttributeKey<?>, Object>> rootAttrs = parent.attrs();
        if (!rootAttrs.hasNext()) {
            return ownAttrsIt;
        }

        return new ConcatenatedCopyOnWriteIterator(ownAttrsIt, rootAttrs);
    }

    @Override
    public Iterator<Entry<AttributeKey<?>, Object>> ownAttrs() {
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            return Collections.emptyIterator();
        }
        return new OwnIterator(attributes);
    }

    @Nullable
    @Override
    public AttributesGetters parent() {
        return parent;
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(Object o) {
        return (T) o;
    }

    @Override
    public boolean isEmpty() {
        return !attrs().hasNext();
    }

    @Override
    public int size() {
        return Iterators.size(attrs());
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

    private enum SetAttrMode {
        OLD_VALUE,
        CUR_ATTR
    }

    @VisibleForTesting
    static final class DefaultAttribute<T> extends ReentrantShortLock implements Entry<AttributeKey<T>, T> {

        @Nullable
        private final AttributeKey<T> key;

        @Nullable
        private volatile T value;

        @Nullable
        private DefaultAttribute<?> next;

        DefaultAttribute(AttributeKey<T> key, @Nullable T value) {
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

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }
            final Entry<?, ?> that = (Entry<?, ?>) o;
            return Objects.equal(key, that.getKey()) &&
                   Objects.equal(value, that.getValue());
        }

        @Override
        public int hashCode() {
            if (key == null) {
                return 0;
            }

            int hashCode = key.hashCode();
            final T value = getValue();
            if (value != null) {
                hashCode = 31 * hashCode + value.hashCode();
            }
            return hashCode;
        }

        @Override
        public String toString() {
            return key == null ? "<HEAD>" : key + "=" + value;
        }
    }

    private static final class OwnIterator implements Iterator<Entry<AttributeKey<?>, Object>> {

        private final AtomicReferenceArray<DefaultAttribute<?>> attributes;

        private int idx = -1;
        @Nullable
        private DefaultAttribute<?> next;

        OwnIterator(AtomicReferenceArray<DefaultAttribute<?>> attributes) {
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
            loop:
            for (;;) {
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

    private class CopyOnWriteIterator implements Iterator<Entry<AttributeKey<?>, Object>> {
        private final Iterator<Entry<AttributeKey<?>, Object>> rootAttrs;

        CopyOnWriteIterator(Iterator<Entry<AttributeKey<?>, Object>> rootAttrs) {
            this.rootAttrs = rootAttrs;
        }

        @Override
        public boolean hasNext() {
            return rootAttrs.hasNext();
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public Entry<AttributeKey<?>, Object> next() {
            return new CopyOnWriteAttribute(rootAttrs.next());
        }
    }

    private final class ConcatenatedCopyOnWriteIterator implements Iterator<Entry<AttributeKey<?>, Object>> {

        private final Iterator<Entry<AttributeKey<?>, Object>> childIt;
        private final Iterator<Entry<AttributeKey<?>, Object>> rootIt;

        // Need to prefetch next to check whether the attribute in root whose key is the same to the attribute
        // in the child so that this iterator does not yield that attribute.
        @Nullable
        private Entry<AttributeKey<?>, Object> next;

        ConcatenatedCopyOnWriteIterator(Iterator<Entry<AttributeKey<?>, Object>> childIt,
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

        @Nullable
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
                @SuppressWarnings("unchecked")
                final Entry<AttributeKey<T>, T> newChildAttr = (Entry<AttributeKey<T>, T>)
                        setAttr(rootAttr.getKey(), value, SetAttrMode.CUR_ATTR);
                this.childAttr = newChildAttr;
                return rootAttr.getValue();
            }

            final T old = childAttr.getValue();
            childAttr.setValue(value);
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }

            final Entry<?, ?> that = (Entry<?, ?>) o;
            return getKey().equals(that.getKey()) &&
                   Objects.equal(getValue(), that.getValue());
        }

        @Override
        public int hashCode() {
            int hashCode = getKey().hashCode();
            final T value = getValue();
            if (value != null) {
                hashCode = 31 * hashCode + value.hashCode();
            }
            return hashCode;
        }

        @Override
        public String toString() {
            return firstNonNull(childAttr, rootAttr).toString();
        }
    }
}
