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

package com.linecorp.armeria.client.auth.oauth2;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.auth.oauth2.SerialFuture;

/**
 * Holds a token object and facilitates its lifecycle based on a number of procedures,
 * such as supply, update, load, store.
 * @param <T> the type of the token object
 */
class TokenLifecycleManager<T> {

    /**
     * Holds a reference to the token.
     */
    private final AtomicReference<T> ref;

    /**
     * Executes token operations serially on a separate thread.
     */
    private final SerialFuture serialFuture;

    private final BiPredicate<? super T, Instant> validator;

    private final Predicate<? super T> isUpdatable;

    private final Predicate<Throwable> shallResupply;

    private final Function<? super T, CompletionStage<? extends T>> serviceSupplier;

    @Nullable
    private final Function<? super T, CompletionStage<? extends T>> serviceUpdater;

    @Nullable
    private final Supplier<? extends T> storageSupplier;

    @Nullable
    private final Consumer<? super T> storageConsumer;

    /**
     * Constructs new {@link TokenLifecycleManager}.
     * @param validator A {@link BiPredicate} that tests the token for validity at the {@link Instant} time.
     * @param isUpdatable A {@link Predicate} that tests whether the token object can be updated or re-supplied.
     * @param shallResupply A {@link Predicate} that tests whether given {@link Throwable} indicates that
     *                      the token shall be re-supplied after the update operation failure.
     * @param serviceSupplier A {@link Function} that supplies an initial token object.
     * @param serviceUpdater A {@link Function} that updates token object.
     * @param storageSupplier A {@link Supplier} that can optionally load previously stored token object.
     * @param storageConsumer A {@link Consumer} that can optionally store token object.
     * @param executor An optional {@link Executor} that facilitates asynchronous supply and update operations.
     *                 A new single thread {@link Executor} will be created using
     *                 {@link Executors#newSingleThreadExecutor()} if the {@code null}.
     */
    TokenLifecycleManager(BiPredicate<? super T, Instant> validator,
                          Predicate<? super T> isUpdatable, Predicate<Throwable> shallResupply,
                          Function<? super T, CompletionStage<? extends T>> serviceSupplier,
                          @Nullable Function<? super T, CompletionStage<? extends T>> serviceUpdater,
                          @Nullable Supplier<? extends T> storageSupplier,
                          @Nullable Consumer<? super T> storageConsumer,
                          @Nullable Executor executor) {
        ref = new AtomicReference<>();
        serialFuture = new SerialFuture(executor);
        this.validator = requireNonNull(validator, "validator");
        this.isUpdatable = requireNonNull(isUpdatable, "isUpdatable");
        this.shallResupply = requireNonNull(shallResupply, "shallResupply");
        this.serviceSupplier = requireNonNull(serviceSupplier, "serviceSupplier");
        this.serviceUpdater = serviceUpdater;
        this.storageSupplier = storageSupplier;
        this.storageConsumer = storageConsumer;
    }

    /**
     * Provides managed token object asynchronously. This operation may involve initial token supply or
     * token update operation, if required.
     */
    CompletionStage<T> get() {
        final T t = ref.get();
        if (t != null) {
            // token already present
            return validateOrUpdate(t);
        }

        // token is not yet present
        // try to supply an initial token serially using a serial execution
        return serialFuture.executeAsync(this::supplySerially);
    }

    private CompletionStage<T> supplySerially() {
        // re-check if the token already present
        T t = ref.get();
        if (t != null) {
            // token already present
            return validateOrUpdate(t, false);
        }

        // token not yet loaded
        // try loading from storage
        t = load();
        if (t != null) {
            // token loaded
            return validateOrUpdate(t, true);
        }

        // token has never been supplied
        // otherwise, supply initial value
        return supplyInitial();
    }

    /**
     * Updates the token object if it's invalid at the given {@link Instant} time.
     */
    private CompletionStage<T> updateSerially(Instant instant) {
        // after acquiring the lock, re-check if it's a valid token
        final T t = ref.get();
        if (validator.test(t, instant)) {
            // simply return a valid token
            return CompletableFuture.completedFuture(t);
        }
        // otherwise, update it
        return update(t);
    }

    /**
     * Validates the token object and updates it using a serial execution.
     */
    private CompletionStage<T> validateOrUpdate(T t) {
        // check if it's still valid
        final Instant instant = Instant.now();
        if (validator.test(t, instant)) {
            // simply return a valid token
            return CompletableFuture.completedFuture(t);
        } else {
            // try to update the token serially using an executor
            return serialFuture.executeAsync(() -> updateSerially(instant));
        }
    }

    /**
     * Validates the token object and updates it, if necessary.
     */
    private CompletionStage<T> validateOrUpdate(T t, boolean reset) {
        // check if it's still valid
        final Instant instant = Instant.now();
        if (validator.test(t, instant)) {
            // simply return a valid token
            if (reset) {
                ref.set(t); // reset the token reference
            }
            return CompletableFuture.completedFuture(t);
        } else {
            // update token exclusively
            return update(t);
        }
    }

    /**
     * Updates or re-supplies existing token object.
     */
    private CompletionStage<T> update(T old) {
        try {
            return supplyUpdated(old);
        } catch (Exception e) {
            if (shallResupply.test(Exceptions.peel(e))) {
                // token update failed, try to re-supply access token instead
                return supplyInitial();
            } else {
                final CompletableFuture<T> failureFuture = new CompletableFuture<>();
                failureFuture.completeExceptionally(e);
                return failureFuture;
            }
        }
    }

    /**
     * Supplies and initial token object using {@code serviceSupplier} and optionally stores it.
     */
    private CompletionStage<T> supplyInitial() {
        return serviceSupplier.apply(null).thenApply(supplied -> {
            ref.set(supplied); // reset the token reference
            store(supplied);
            return supplied;
        });
    }

    /**
     * Supplies and initial token object using {@code serviceSupplier} and optionally stores it.
     */
    private CompletionStage<T> supplyUpdated(T old) {
        return invokeUpdateOrResupply(old).thenApply(updated -> {
            ref.set(updated); // reset the token reference
            store(updated);
            return updated;
        });
    }

    /**
     * Updates or re-supplies token object based on whether {@code serviceUpdater} function is available and
     * whether the token {@code isUpdatable}.
     */
    private CompletionStage<? extends T> invokeUpdateOrResupply(T old) {
        if (serviceUpdater != null && isUpdatable.test(old)) {
            return serviceUpdater.apply(old);
        }
        return serviceSupplier.apply(old);
    }

    /**
     * Loads previously stored token object using {@code storageSupplier}.
     * Returns {@code null} if the {@code storageSupplier} is not available or
     * if the object has not been previously stored.
     */
    @Nullable
    private T load() {
        return (storageSupplier == null) ? null : storageSupplier.get();
    }

    /**
     * Stores token object using {@code storageConsumer}.
     */
    private void store(T t) {
        if (storageConsumer != null) {
            storageConsumer.accept(t); // store token to an optional storage (e.g. secret store)
        }
    }
}
