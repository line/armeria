package com.linecorp.armeria.client.limit;

import java.util.concurrent.CompletableFuture;

public interface ConcurrencyLimit<Context> {
    CompletableFuture<Permit> acquire(Context ctx);

    interface Permit {
        void release();
    }

}
