/*
 * Copyright 2016 LINE Corporation
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
/*
 * Copyright 2012 The Netty Project
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
package com.linecorp.armeria.internal;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;

/**
 * Default {@link AttributeMap} implementation which use simple synchronization per bucket to keep the memory overhead
 * as low as possible.
 * <p>
 * Note: This class has been forked from {@link io.netty.util.DefaultAttributeMap}.
 */
public class DefaultAttributeMap implements AttributeMap {

    @SuppressWarnings({ "rawtypes", "AtomicFieldUpdaterIssues" })
    private static final AtomicReferenceFieldUpdater<DefaultAttributeMap, AtomicReferenceArray> updater =
            AtomicReferenceFieldUpdater.newUpdater(DefaultAttributeMap.class,
                                                   AtomicReferenceArray.class, "attributes");

    private static final int BUCKET_SIZE = 4;
    private static final int MASK = BUCKET_SIZE  - 1;

    // Initialize lazily to reduce memory consumption; updated by AtomicReferenceFieldUpdater above.
    @VisibleForTesting
    @SuppressWarnings("UnusedDeclaration")
    volatile AtomicReferenceArray<DefaultAttribute<?>> attributes;

    @SuppressWarnings("unchecked")
    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            // Not using ConcurrentHashMap due to high memory consumption.
            attributes = new AtomicReferenceArray<>(BUCKET_SIZE);

            if (!updater.compareAndSet(this, null, attributes)) {
                attributes = this.attributes;
            }
        }

        int i = index(key);
        DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {
            // No head exists yet which means we may be able to add the attribute without synchronization and just
            // use compare and set. At worst we need to fallback to synchronization
            head = new DefaultAttribute(key);
            if (attributes.compareAndSet(i, null, head)) {
                // we were able to add it so return the head right away
                return (Attribute<T>) head;
            } else {
                head = attributes.get(i);
            }
        }

        synchronized (head) {
            DefaultAttribute<?> curr = head;
            for (;;) {
                if (!curr.removed && curr.key == key) {
                    return (Attribute<T>) curr;
                }

                DefaultAttribute<?> next = curr.next;
                if (next == null) {
                    DefaultAttribute<T> attr = new DefaultAttribute<>(head, key);
                    curr.next =  attr;
                    attr.prev = curr;
                    return attr;
                } else {
                    curr = next;
                }
            }
        }
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        AtomicReferenceArray<DefaultAttribute<?>> attributes = this.attributes;
        if (attributes == null) {
            // no attribute exists
            return false;
        }

        int i = index(key);
        DefaultAttribute<?> head = attributes.get(i);
        if (head == null) {
            // No attribute exists which point to the bucket in which the head should be located
            return false;
        }

        // check on the head can be done without synchronization
        if (head.key == key && !head.removed) {
            return true;
        }

        synchronized (head) {
            // we need to synchronize on the head
            DefaultAttribute<?> curr = head.next;
            while (curr != null) {
                if (!curr.removed && curr.key == key) {
                    return true;
                }
                curr = curr.next;
            }
            return false;
        }
    }

    /**
     * Returns all {@link Attribute}s this map contains.
     */
    public Iterator<Attribute<?>> attrs() {
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
        for (Iterator<Attribute<?>> i = attrs(); i.hasNext();) {
            final Attribute<?> a = i.next();
            helper.add(a.key().name(), a.get());
        }
        return helper.toString();
    }

    @SuppressWarnings("serial")
    private static final class DefaultAttribute<T> extends AtomicReference<T> implements Attribute<T> {

        private static final long serialVersionUID = -2661411462200283011L;

        // The head of the linked-list this attribute belongs to, which may be itself
        private final DefaultAttribute<?> head;
        private final AttributeKey<T> key;

        // Double-linked list to prev and next node to allow fast removal
        private DefaultAttribute<?> prev;
        private DefaultAttribute<?> next;

        // Will be set to true one the attribute is removed via getAndRemove() or remove()
        private volatile boolean removed;

        DefaultAttribute(DefaultAttribute<?> head, AttributeKey<T> key) {
            this.head = head;
            this.key = key;
        }

        DefaultAttribute(AttributeKey<T> key) {
            head = this;
            this.key = key;
        }

        @Override
        public AttributeKey<T> key() {
            return key;
        }

        @Override
        public T setIfAbsent(T value) {
            while (!compareAndSet(null, value)) {
                T old = get();
                if (old != null) {
                    return old;
                }
            }
            return null;
        }

        @Override
        public T getAndRemove() {
            removed = true;
            T oldValue = getAndSet(null);
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
                // We only update the linked-list structure if prev != null because if it is null this
                // DefaultAttribute acts also as head. The head must never be removed completely and just be
                // marked as removed as all synchronization is done on the head itself for each bucket.
                // The head itself will be GC'ed once the DefaultAttributeMap is GC'ed. So at most 5 heads will
                // be removed lazy as the array size is 5.
                if (prev != null) {
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
    }

    private static final class IteratorImpl implements Iterator<Attribute<?>> {

        private final AtomicReferenceArray<DefaultAttribute<?>> attributes;

        private int i = -1;
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
            if (next == null) {
                throw new NoSuchElementException();
            }

            final DefaultAttribute<?> next = this.next;
            this.next = findNext(next.next);
            return next;
        }

        private DefaultAttribute<?> findNext(DefaultAttribute<?> next) {
            loop: for (;;) {
                if (next == null) {
                    for (i++; i < attributes.length(); i++) {
                        final DefaultAttribute<?> attr = attributes.get(i);
                        if (attr != null) {
                            next = attr;
                            break;
                        }
                    }

                    if (i == attributes.length()) {
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
}
