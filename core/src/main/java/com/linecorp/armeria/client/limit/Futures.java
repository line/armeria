/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.limit;

import java.util.concurrent.CompletableFuture;

import io.netty.util.concurrent.Future;

final class Futures {
    private Futures() {}

    static <V> CompletableFuture<V> toCompletableFuture(Future<V> future) {
        final CompletableFuture<V> adapter = new CompletableFuture<>();
        if (future.isDone()) {
            if (future.isSuccess()) {
                adapter.complete(future.getNow());
            } else {
                adapter.completeExceptionally(future.cause());
            }
        } else {
            future.addListener((Future<V> f) -> {
                if (f.isSuccess()) {
                    adapter.complete(f.getNow());
                } else {
                    adapter.completeExceptionally(f.cause());
                }
            });
        }
        return adapter;
    }
}
