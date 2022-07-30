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

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AggregationOptions;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class DefaultHttpAggregationOptions<T extends HttpObject, U extends AggregatedHttpMessage>
        implements HttpAggregationOptions<T, U> {

    private final AggregationOptions<T, U> delegate;
    @Nullable
    private final ByteBufAllocator alloc;

    DefaultHttpAggregationOptions(AggregationOptions<T, U> delegate, @Nullable ByteBufAllocator alloc) {
        this.delegate = delegate;
        this.alloc = alloc;
    }

    @Override
    public Function<List<T>, U> aggregator() {
        return delegate.aggregator();
    }

    @Override
    public EventExecutor executor() {
        return delegate.executor();
    }

    @Override
    public boolean cacheResult() {
        return delegate.cacheResult();
    }

    @Override
    public boolean withPooledObjects() {
        return delegate.withPooledObjects();
    }

    @Override
    public ByteBufAllocator alloc() {
        return alloc;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpAggregationOptions)) {
            return false;
        }
        final HttpAggregationOptions<?, ?> that = (HttpAggregationOptions<?, ?>) o;
        return delegate.equals(o) && Objects.equals(alloc, that.alloc());
    }

    @Override
    public int hashCode() {
        int hashCode = delegate.hashCode();
        if (alloc != null) {
            hashCode = hashCode * 31 + alloc.hashCode();
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("delegate", delegate)
                          .add("alloc", alloc)
                          .toString();
    }
}
