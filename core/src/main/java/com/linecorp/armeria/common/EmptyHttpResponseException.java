/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An exception which is thrown when an {@link HttpResponse} without any headers
 * or body is attempted to be sent over the wire.
 */
@UnstableApi
public final class EmptyHttpResponseException extends RuntimeException {

    private static final long serialVersionUID = -6959143965708016166L;

    private static final EmptyHttpResponseException INSTANCE =
            new EmptyHttpResponseException(null, null, false, false);

    /**
     * Returns a {@link EmptyHttpResponseException} which may be a singleton or a new instance,
     * depending on {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public static EmptyHttpResponseException get() {
        return isSampled() ? new EmptyHttpResponseException(null, null, true, true) : INSTANCE;
    }

    private static boolean isSampled() {
        return Flags.verboseExceptionSampler().isSampled(EmptyHttpResponseException.class);
    }

    private EmptyHttpResponseException(@Nullable String message, @Nullable Throwable cause,
                                       boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
