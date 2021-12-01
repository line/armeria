package com.linecorp.armeria.internal.common;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

final class HeadersFuture<T> extends UnmodifiableFuture<T> {

    @Override
    protected void doComplete(@Nullable T value) {
        super.doComplete(value);
    }

    @Override
    protected void doCompleteExceptionally(Throwable cause) {
        super.doCompleteExceptionally(cause);
    }
}