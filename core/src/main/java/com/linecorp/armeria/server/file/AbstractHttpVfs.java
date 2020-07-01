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
package com.linecorp.armeria.server.file;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * A skeletal {@link HttpVfs} implementation.
 */
public abstract class AbstractHttpVfs implements HttpVfs {

    private static final UnmodifiableFuture<List<String>> EMPTY_LIST_FUTURE =
            UnmodifiableFuture.completedFuture(ImmutableList.of());

    @Override
    public CompletableFuture<Boolean> canList(Executor fileReadExecutor, String path) {
        return UnmodifiableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<List<String>> list(Executor fileReadExecutor, String path) {
        return EMPTY_LIST_FUTURE;
    }

    /**
     * Returns the {@link #meterTag()} of this {@link HttpVfs}.
     */
    @Override
    public String toString() {
        return meterTag();
    }
}
