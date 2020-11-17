/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

final class LazyList<E> implements List<E> {

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<LazyList, List> delegateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(LazyList.class, List.class, "delegate");

    private final Supplier<List<E>> delegateSupplier;

    @Nullable
    private volatile List<E> delegate;

    LazyList(Supplier<List<E>> delegateSupplier) {
        this.delegateSupplier = delegateSupplier;
    }

    @Override
    public int size() {
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.size();
        }

        return setDelegate().size();
    }

    @Override
    public boolean isEmpty() {
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.isEmpty();
        }

        return setDelegate().isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        requireNonNull(o, "o");
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.contains(o);
        }

        return setDelegate().contains(o);
    }

    @Override
    public Iterator<E> iterator() {
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.iterator();
        }

        return setDelegate().iterator();
    }

    @Override
    public Object[] toArray() {
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.toArray();
        }

        return setDelegate().toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        requireNonNull(a, "a");
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.toArray(a);
        }

        return setDelegate().toArray(a);
    }

    @Override
    public boolean add(E e) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(int index, E element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public E remove(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        requireNonNull(c, "c");
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.containsAll(c);
        }

        return setDelegate().containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public E get(int index) {
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.get(index);
        }

        return setDelegate().get(index);
    }

    @Override
    public E set(int index, E element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(Object o) {
        requireNonNull(o, "o");
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.indexOf(o);
        }

        return setDelegate().indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        requireNonNull(o, "o");
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.lastIndexOf(o);
        }

        return setDelegate().lastIndexOf(o);
    }

    @Override
    public ListIterator<E> listIterator() {
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.listIterator();
        }

        return setDelegate().listIterator();
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.listIterator(index);
        }

        return setDelegate().listIterator(index);
    }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate.subList(fromIndex, toIndex);
        }

        return setDelegate().subList(fromIndex, toIndex);
    }

    private List<E> setDelegate() {
        final List<E> supplied = ImmutableList.copyOf(delegateSupplier.get());
        assert supplied != null;
        if (delegateUpdater.compareAndSet(this, null, supplied)) {
            return supplied;
        }
        final List<E> delegate = this.delegate;
        assert delegate != null;
        return delegate;
    }
}
