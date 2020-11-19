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

import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.collect.ForwardingList;
import com.google.common.collect.ImmutableList;

final class LazyList<E> extends ForwardingList<E> {

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
    protected List<E> delegate() {
        final List<E> delegate = this.delegate;
        if (delegate != null) {
            return delegate;
        }
        final List<E> supplied = ImmutableList.copyOf(delegateSupplier.get());
        if (delegateUpdater.compareAndSet(this, null, supplied)) {
            return supplied;
        }

        final List<E> delegate0 = this.delegate;
        assert delegate0 != null;
        return delegate0;
    }
}
