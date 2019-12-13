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
/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.AttributeMap;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Default {@link AttributeMap} implementation which use simple synchronization per bucket to keep the memory
 * overhead as low as possible.
 *
 * <p>Note: This class has been forked from {@link io.netty.util.DefaultAttributeMap}, with the following
 * changes:
 *
 * <ul>
 *   <li>Add {@link #attrs()}</li>
 *   <li>Increase the default bucket size from 4 to 8</li>
 * </ul>
 */
public class DefaultAttributeMap implements AttributeMap {

    // Forked from Netty 4.1.34 at 506f0d8f8c10e1b24924f7d992a726d7bdd2e486

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultAttributeMap, AtomicReferenceArray> updater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultAttributeMap.class,
                                                   AtomicReferenceArray.class,
                                                   "attributes");

    private static final int BUCKET_SIZE = 8;
    private static final int MASK = BUCKET_SIZE  - 1;

    @Nullable
    private final AttributeMap root;

    // Initialize lazily to reduce memory consumption; updated by AtomicReferenceFieldUpdater above.
    @VisibleForTesting
    @Nullable
    volatile AtomicReferenceArray<DefaultAttribute<?>> attributes;

    /**
     * Creates a new instance.
     */
    public DefaultAttributeMap(@Nullable AttributeMap root) {
        this.root = root;
    }

    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        return attr(key, false);
    }

    public <T> Attribute<T> ownAttr(AttributeKey<T> key) {
        return attr(key, true);
    }

    private <T> Attribute<T> attr(AttributeKey<T> key, boolean ownAttr) {
        requireNonNull(key, "key");
        AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            if (!ownAttr && root != null && root.hasAttr(key)) {
                return new CopyOnWriteAttribute<>(root.attr(key));
            }

            // Not using ConcurrentHashMap due to high memory consumption.
            attributes = new AtomicReferenceArray<>(BUCKET_SIZE);

            if (!updater.compareAndSet(this, null, attributes)) {
                attributes = this.attributes;
            }
        }

        final int i = index(key);
        DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {
            if (!ownAttr && root != null && root.hasAttr(key)) {
                return new CopyOnWriteAttribute<>(root.attr(key));
            }

            // No head exists yet which means we may be able to add the attribute without synchronization and
            // just use compare and set. At worst we need to fallback to synchronization and waste two
            // allocations.
            head = new DefaultAttribute();
            final DefaultAttribute<T> attr = new DefaultAttribute<>(head, key);
            head.next = attr;
            attr.prev = head;
            if (attributes.compareAndSet(i, null, head)) {
                // we were able to add it so return the attr right away
                return attr;
            } else {
                head = attributes.get(i);
            }
        }

        synchronized (head) {
            DefaultAttribute<?> curr = head;
            for (;;) {
                final DefaultAttribute<?> next = curr.next;
                if (next == null) {
                    final DefaultAttribute<T> attr = new DefaultAttribute<>(head, key);
                    curr.next = attr;
                    attr.prev = curr;
                    return attr;
                }

                if (next.key == key && !next.removed) {
                    @SuppressWarnings("unchecked")
                    final Attribute<T> result = (Attribute<T>) next;
                    return result;
                }
                curr = next;
            }
        }
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        return hasAttr(key, false);
    }

    public <T> boolean hasOwnAttr(AttributeKey<T> key) {
        return hasAttr(key, true);
    }

    private <T> boolean hasAttr(AttributeKey<T> key, boolean ownAttr) {
        requireNonNull(key, "key");

        final AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            // no attribute exists
            return ownAttr ? false : hasAttrInRoot(key);
        }

        final int i = index(key);
        final DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {
            // No attribute exists which point to the bucket in which the head should be located
            return ownAttr ? false : hasAttrInRoot(key);
        }

        // We need to synchronize on the head.
        synchronized (head) {
            // Start with head.next as the head itself does not store an attribute.
            DefaultAttribute<?> curr = head.next;
            while (curr != null) {
                if (curr.key == key && !curr.removed) {
                    return true;
                }
                curr = curr.next;
            }
        }
        return ownAttr ? false : hasAttrInRoot(key);
    }

    private <T> boolean hasAttrInRoot(AttributeKey<T> key) {
        if (root != null) {
            return root.hasAttr(key);
        }
        return false;
    }

    @Override
    public Iterator<Attribute<?>> attrs() {
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            return root != null ? root.attrs() : Collections.emptyIterator();
        }

        final Iterator<Attribute<?>> ownAttrsIt = new IteratorImpl(attributes);
        return root != null ? new ConcatenatedIteratorImpl(ownAttrsIt, root.attrs())
                            : ownAttrsIt;
    }

    public Iterator<Attribute<?>> ownAttrs() {
        final AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            return Collections.emptyIterator();
        }
        return new IteratorImpl(attributes);
    }

    private static int index(AttributeKey<?> key) {
        return key.id() & MASK;
    }

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper("");
        for (final Iterator<Attribute<?>> i = attrs(); i.hasNext();) {
            final Attribute<?> a = i.next();
            helper.add(a.key().name(), a.get());
        }
        return helper.toString();
    }

    private static final class DefaultAttribute<T> extends AtomicReference<T> implements Attribute<T> {

        private static final long serialVersionUID = -2661411462200283011L;

        // The head of the linked-list this attribute belongs to
        private final DefaultAttribute<?> head;
        @Nullable
        private final AttributeKey<T> key;

        // Double-linked list to prev and next node to allow fast removal
        @Nullable
        private DefaultAttribute<?> prev;
        @Nullable
        private DefaultAttribute<?> next;

        // Will be set to true one the attribute is removed via getAndRemove() or remove()
        private volatile boolean removed;

        DefaultAttribute(DefaultAttribute<?> head, AttributeKey<T> key) {
            this.head = head;
            this.key = key;
        }

        // Special constructor for the head of the linked-list.
        DefaultAttribute() {
            head = this;
            key = null;
        }

        @Nullable
        @Override
        public AttributeKey<T> key() {
            return key;
        }

        @Nullable
        @Override
        public T setIfAbsent(T value) {
            while (!compareAndSet(null, value)) {
                final T old = get();
                if (old != null) {
                    return old;
                }
            }
            return null;
        }

        @Nullable
        @Override
        public T getAndRemove() {
            removed = true;
            final T oldValue = getAndSet(null);
            remove0();
            return oldValue;
        }

        @Override
        public void remove() {
            removed = true;
            set(null);
            remove0();
        }

        private void remove0() {
            synchronized (head) {
                if (prev == null) {
                    // Removed before.
                    return;
                }

                prev.next = next;

                if (next != null) {
                    next.prev = prev;
                }

                // Null out prev and next - this will guard against multiple remove0() calls which may corrupt
                // the linked list for the bucket.
                prev = null;
                next = null;
            }
        }
    }

    private final class CopyOnWriteAttribute<T> implements Attribute<T> {

        private final Attribute<T> attrInRoot;

        @Nullable
        private Attribute<T> attrInThis;

        CopyOnWriteAttribute(Attribute<T> attrInRoot) {
            this.attrInRoot = attrInRoot;
        }

        @Override
        public AttributeKey<T> key() {
            if (attrInThis != null) {
                return attrInThis.key();
            }
            return attrInRoot.key();
        }

        @Override
        public T get() {
            if (attrInThis != null) {
                return attrInThis.get();
            }
            return attrInRoot.get();
        }

        @Override
        public void set(T value) {
            if (attrInThis != null) {
                attrInThis.set(value);
            } else {
                final Attribute<T> attrInThis = attr(attrInRoot.key(), true);
                this.attrInThis = attrInThis;
                attrInThis.set(value);
            }
        }

        @Override
        public T getAndSet(T value) {
            if (attrInThis != null) {
                return attrInThis.getAndSet(value);
            }
            set(value);
            return attrInRoot.get();
        }

        @Override
        public T setIfAbsent(T value) {
            if (attrInThis != null) {
                return attrInThis.setIfAbsent(value);
            }
            final Attribute<T> attrInThis = attr(attrInRoot.key(), true);
            this.attrInThis = attrInThis;
            return attrInThis.setIfAbsent(value);
        }

        @Override
        public T getAndRemove() {
            if (attrInThis != null) {
                return attrInThis.getAndRemove();
            }
            return attrInRoot.get();
        }

        @Override
        public boolean compareAndSet(T oldValue, T newValue) {
            if (attrInThis != null) {
                return attrInThis.compareAndSet(oldValue, newValue);
            }
            // Do I need to just return false here because this does not guarantee atomicity?
            if (attrInRoot.get() == oldValue) {
                final Attribute<T> attrInThis = attr(attrInRoot.key(), true);
                this.attrInThis = attrInThis;
                return attrInThis.compareAndSet(null, newValue);
            }
            return false;
        }

        @Override
        public void remove() {
            if (attrInThis != null) {
                attrInThis.remove();
            }
        }
    }

    private static final class IteratorImpl implements Iterator<Attribute<?>> {

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
        public Attribute<?> next() {
            final DefaultAttribute<?> next = this.next;
            if (next == null) {
                throw new NoSuchElementException();
            }
            this.next = findNext(next.next);
            return next;
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

                while (next.removed) {
                    if ((next = next.next) == null) {
                        continue loop;
                    }
                }

                return next;
            }
        }
    }

    private final class ConcatenatedIteratorImpl implements Iterator<Attribute<?>> {

        private final Iterator<Attribute<?>> thisIt;
        private final Iterator<Attribute<?>> rootIt;

        @Nullable
        private Attribute<?> next;

        ConcatenatedIteratorImpl(Iterator<Attribute<?>> thisIt, Iterator<Attribute<?>> rootIt) {
            this.thisIt = thisIt;
            this.rootIt = rootIt;

            if (thisIt.hasNext()) {
                next = thisIt.next();
            } else if (rootIt.hasNext()) {
                next = new CopyOnWriteAttribute<>(rootIt.next());
            } else {
                next = null;
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Attribute<?> next() {
            final Attribute<?> next = this.next;
            if (next == null) {
                throw new NoSuchElementException();
            }

            if (thisIt.hasNext()) {
                this.next = thisIt.next();
            } else {
                // Skip the attribute in rootIt if it's in thisAttributeMap.
                for (;;) {
                    if (rootIt.hasNext()) {
                        final Attribute<?> tempNext = rootIt.next();
                        if (!hasOwnAttr(tempNext.key())) {
                            this.next = new CopyOnWriteAttribute<>(tempNext);
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
