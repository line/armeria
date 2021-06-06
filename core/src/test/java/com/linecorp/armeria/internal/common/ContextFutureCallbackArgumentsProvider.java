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

package com.linecorp.armeria.internal.common;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.RequestContext;

public class ContextFutureCallbackArgumentsProvider implements ArgumentsProvider {
    static final class CallbackResult {
        final AtomicReference<RequestContext> context = new AtomicReference<>();
        final AtomicBoolean called = new AtomicBoolean();
    }

    private final Function<CallbackResult, Void> fn = result -> {
        result.called.set(true);
        result.context.set(RequestContext.current());
        return null;
    };

    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
        final Arguments thenApply = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, CallbackResult>) (future, result) -> {
                    future.thenApply(res -> fn.apply(result))
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenApplyAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.thenApplyAsync(res -> fn.apply(result), MoreExecutors.directExecutor())
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenAccept = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.thenAccept(res -> fn.apply(result))
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenAcceptAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.thenAcceptAsync(res -> fn.apply(result), MoreExecutors.directExecutor())
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenRun = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.thenRun(() -> fn.apply(result))
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenRunAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.thenRunAsync(() -> fn.apply(result), MoreExecutors.directExecutor())
                          .exceptionally(cause -> fn.apply(result));
                });
        final CompletableFuture<Void> completedFuture = CompletableFuture.completedFuture(null);
        final Arguments thenCombine = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.thenCombine(completedFuture, (a, b) -> fn.apply(result))
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenCombineAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.thenCombineAsync(completedFuture, (a, b) -> fn.apply(result),
                                            MoreExecutors.directExecutor())
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenAcceptBoth = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.thenAcceptBoth(completedFuture, (a, b) -> fn.apply(result))
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenAcceptBothAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.thenAcceptBothAsync(completedFuture, (a, b) -> fn.apply(result),
                                               MoreExecutors.directExecutor())
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments runAfterBoth = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.runAfterBoth(completedFuture, () -> fn.apply(result))
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments runAfterBothAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.runAfterBothAsync(completedFuture, () -> fn.apply(result),
                                             MoreExecutors.directExecutor())
                          .exceptionally(cause -> fn.apply(result));
                });
        final CompletableFuture<Object> neverCompleteFuture = new CompletableFuture<>();
        final Arguments applyToEither = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, CallbackResult>) (future, result) -> {
                    future.applyToEither(neverCompleteFuture, a -> fn.apply(result))
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments applyToEitherAsync = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, CallbackResult>) (future, result) -> {
                    future.applyToEitherAsync(neverCompleteFuture, a -> fn.apply(result),
                                              MoreExecutors.directExecutor())
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments acceptEither = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, CallbackResult>) (future, result) -> {
                    future.acceptEither(neverCompleteFuture, a -> fn.apply(result))
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments acceptEitherAsync = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, CallbackResult>) (future, result) -> {
                    future.acceptEitherAsync(neverCompleteFuture, a -> fn.apply(result),
                                             MoreExecutors.directExecutor())
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments runAfterEither = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, CallbackResult>) (future, result) -> {
                    future.runAfterEither(neverCompleteFuture, () -> fn.apply(result))
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments runAfterEitherAsync = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, CallbackResult>) (future, result) -> {
                    future.runAfterEitherAsync(neverCompleteFuture, () -> fn.apply(result),
                                               MoreExecutors.directExecutor())
                          .exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenCompose = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, CallbackResult>) (future, result) -> {
                    future.thenCompose(f -> {
                        fn.apply(result);
                        return null;
                    }).exceptionally(cause -> fn.apply(result));
                });
        final Arguments thenComposeAsync = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, CallbackResult>) (future, result) -> {
                    future.thenComposeAsync(f -> {
                        fn.apply(result);
                        return null;
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> fn.apply(result));
                });
        final Arguments whenComplete = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.whenComplete((res, cause) -> fn.apply(result));
                });
        final Arguments whenCompleteAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.whenCompleteAsync((res, cause) -> fn.apply(result), MoreExecutors.directExecutor());
                });
        final Arguments handle = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.handle((res, cause) -> fn.apply(result));
                });
        final Arguments handleAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, CallbackResult>) (future, result) -> {
                    future.handleAsync((res, cause) -> fn.apply(result), MoreExecutors.directExecutor());
                });
        return Stream.of(thenApply, thenApplyAsync,
                         thenAccept, thenAcceptAsync,
                         thenRun, thenRunAsync,
                         thenCombine, thenCombineAsync,
                         thenAcceptBoth, thenAcceptBothAsync,
                         runAfterBoth, runAfterBothAsync,
                         applyToEither, applyToEitherAsync,
                         acceptEither, acceptEitherAsync,
                         runAfterEither, runAfterEitherAsync,
                         thenCompose, thenComposeAsync,
                         whenComplete, whenCompleteAsync,
                         handle, handleAsync);
    }

    Function<CallbackResult, Void> fn() {
        return fn;
    }
}
