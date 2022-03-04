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

package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import io.netty.util.concurrent.Future;

public final class NettyFutureUtil {

    public static <A> CompletableFuture<A> toCompletableFuture(Future<A> future) {
        requireNonNull(future, "future");

        final CompletableFuture<A> cf = new CompletableFuture<>();
        if (future.isDone()) {
            toCompletableFuture(future, cf);
        } else {
            future.addListener((Future<A> future0) -> toCompletableFuture(future0, cf));
        }
        return cf;
    }

    private static <A> void toCompletableFuture(Future<A> future, CompletableFuture<A> cf) {
        if (future.isSuccess()) {
            cf.complete(future.getNow());
        } else {
            cf.completeExceptionally(future.cause());
        }
    }

    private NettyFutureUtil() {}
}
