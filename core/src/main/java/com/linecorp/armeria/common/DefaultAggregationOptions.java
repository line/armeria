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

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;

final class DefaultAggregationOptions implements AggregationOptions {

    @Nullable
    private final EventExecutor executor;
    @Nullable
    private final ByteBufAllocator alloc;
    private final boolean preferCached;
    private final boolean cacheResult;

    DefaultAggregationOptions(@Nullable EventExecutor executor, @Nullable ByteBufAllocator alloc,
                              boolean preferCached, boolean cacheResult) {
        this.executor = executor;
        this.alloc = alloc;
        this.preferCached = preferCached;
        this.cacheResult = cacheResult;
    }

    @Override
    public EventExecutor executor() {
        return executor;
    }

    @Override
    public boolean cacheResult() {
        return cacheResult;
    }

    @Override
    public boolean preferCached() {
        return preferCached;
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
        if (!(o instanceof AggregationOptions)) {
            return false;
        }
        final AggregationOptions that = (AggregationOptions) o;
        return cacheResult == that.cacheResult() &&
               preferCached == that.preferCached() &&
               Objects.equals(executor, that.executor()) &&
               Objects.equals(alloc, that.alloc());
    }

    @Override
    public int hashCode() {
        return Objects.hash(executor, alloc, cacheResult, preferCached);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("executor", executor)
                          .add("alloc", alloc)
                          .add("cacheResult", cacheResult)
                          .add("preferCached", preferCached)
                          .toString();
    }
}
