/*
 * Copyright (c) 2019 LINE Corporation. All rights Reserved.
 * LINE Corporation PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.linecorp.armeria.client.retrofit2;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.logging.RequestLog;

import io.netty.util.AttributeKey;
import retrofit2.Invocation;

/**
 * Retrieves a Retrofit {@link Invocation} associated with a {@link RequestLog}.
 */
public final class InvocationUtil {

    private static final AttributeKey<Invocation> RETROFIT_INVOCATION =
            AttributeKey.valueOf(InvocationUtil.class, "RETROFIT_INVOCATION");

    /**
     * Retrieves a Retrofit {@link Invocation} associated with a {@link RequestLog}.
     */
    @Nullable
    public static Invocation getInvocation(RequestLog log) {
        return log.attr(RETROFIT_INVOCATION).get();
    }

    /**
     * Put {@code Invocation} to {@code RequestLog} if not null.
     */
    static void setInvocation(RequestLog log, @Nullable Invocation invocation) {
        if (invocation == null) {
            return;
        }
        log.attr(RETROFIT_INVOCATION).set(invocation);
    }

    private InvocationUtil() {}
}
