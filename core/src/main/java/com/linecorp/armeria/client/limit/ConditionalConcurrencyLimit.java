package com.linecorp.armeria.client.limit;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.linecorp.armeria.client.ClientRequestContext;

public class ConditionalConcurrencyLimit implements ConcurrencyLimit<ClientRequestContext> {
    private static final CompletableFuture<Permit> READY_PERMIT = CompletableFuture.completedFuture(
            () -> {
            });
    private final Predicate<ClientRequestContext> predicate;
    private final ConcurrencyLimit<ClientRequestContext> delegate;

    public ConditionalConcurrencyLimit(Predicate<ClientRequestContext> predicate,
                                       ConcurrencyLimit<ClientRequestContext> delegate) {
        this.predicate = predicate;
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<Permit> acquire(ClientRequestContext ctx) {
        return predicate.test(ctx) ? delegate.acquire(ctx) : READY_PERMIT;
    }
}
