/*
 * Copyright 2015 LINE Corporation
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

import javax.annotation.Nullable;

/**
 * A {@link RuntimeException} that is raised when a requested invocation did not complete before its deadline.
 *
 * <p>Note that this exception does not provide a stack trace even if
 * {@linkplain Flags#verboseExceptions() the verbose exception mode} is enabled
 * because timeouts often occur massively when the system is under high load and
 * capturing the stack trace is an expensive operation that only makes the situation worse.</p>
 */
public class TimeoutException extends RuntimeException {
    private static final long serialVersionUID = 2887898788270995289L;

    /**
     * Creates a new exception.
     */
    public TimeoutException() {}

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public TimeoutException(@Nullable String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public TimeoutException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public TimeoutException(@Nullable Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new instance with the specified {@code message}, {@code cause}, suppression enabled or
     * disabled, and writable stack trace enabled or disabled.
     */
    protected TimeoutException(@Nullable String message, @Nullable Throwable cause,
                               boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    @Override
    public final Throwable fillInStackTrace() {
        return this;
    }
}
