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
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import com.google.common.util.concurrent.MoreExecutors;

public class ContextFutureCallbackArgumentsProvider implements ArgumentsProvider {

    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
        final Arguments thenApply = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenApply(res -> {
                        called.set(true);
                        return null;
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenApplyAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenApplyAsync(res -> {
                        called.set(true);
                        return null;
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenAccept = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenAccept(res -> {
                        called.set(true);
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenAcceptAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenAcceptAsync(res -> {
                        called.set(true);
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenRun = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenRun(() -> {
                        called.set(true);
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenRunAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenRunAsync(() -> {
                        called.set(true);
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final CompletableFuture<Void> completedFuture = CompletableFuture.completedFuture(null);
        final Arguments thenCombine = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenCombine(completedFuture, (a, b) -> {
                        called.set(true);
                        return null;
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenCombineAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenCombineAsync(completedFuture, (a, b) -> {
                        called.set(true);
                        return null;
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenAcceptBoth = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenAcceptBoth(completedFuture, (a, b) -> {
                        called.set(true);
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenAcceptBothAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.thenAcceptBothAsync(completedFuture, (a, b) -> {
                        called.set(true);
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments runAfterBoth = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.runAfterBoth(completedFuture, () -> {
                        called.set(true);
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments runAfterBothAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.runAfterBothAsync(completedFuture, () -> {
                        called.set(true);
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final CompletableFuture<Object> neverCompleteFuture = new CompletableFuture<>();
        final Arguments applyToEither = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, AtomicBoolean>) (future, called) -> {
                    future.applyToEither(neverCompleteFuture, a -> {
                        called.set(true);
                        return null;
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments applyToEitherAsync = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, AtomicBoolean>) (future, called) -> {
                    future.applyToEitherAsync(neverCompleteFuture, a -> {
                        called.set(true);
                        return null;
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments acceptEither = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, AtomicBoolean>) (future, called) -> {
                    future.acceptEither(neverCompleteFuture, a -> {
                        called.set(true);
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments acceptEitherAsync = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, AtomicBoolean>) (future, called) -> {
                    future.acceptEitherAsync(neverCompleteFuture, a -> {
                        called.set(true);
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments runAfterEither = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, AtomicBoolean>) (future, called) -> {
                    future.runAfterEither(neverCompleteFuture, () -> {
                        called.set(true);
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments runAfterEitherAsync = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, AtomicBoolean>) (future, called) -> {
                    future.runAfterEitherAsync(neverCompleteFuture, () -> {
                        called.set(true);
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenCompose = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, AtomicBoolean>) (future, called) -> {
                    future.thenCompose(f -> {
                        called.set(true);
                        return null;
                    }).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments thenComposeAsync = Arguments.of(
                (BiConsumer<CompletableFuture<Object>, AtomicBoolean>) (future, called) -> {
                    future.thenComposeAsync(f -> {
                        called.set(true);
                        return null;
                    }, MoreExecutors.directExecutor()).exceptionally(cause -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments whenComplete = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.whenComplete((res, cause) -> {
                        called.set(true);
                    });
                });
        final Arguments whenCompleteAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.whenCompleteAsync((res, cause) -> {
                        called.set(true);
                    }, MoreExecutors.directExecutor());
                });
        final Arguments handle = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.handle((res, cause) -> {
                        called.set(true);
                        return null;
                    });
                });
        final Arguments handleAsync = Arguments.of(
                (BiConsumer<CompletableFuture<?>, AtomicBoolean>) (future, called) -> {
                    future.handleAsync((res, cause) -> {
                        called.set(true);
                        return null;
                    }, MoreExecutors.directExecutor());
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
}
