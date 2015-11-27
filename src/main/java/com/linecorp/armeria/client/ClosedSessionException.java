/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client;

import io.netty.util.internal.EmptyArrays;

/**
 * A {@link RuntimeException} raised when the connection to the server has been closed unexpectedly.
 */
public class ClosedSessionException extends RuntimeException {

    private static final long serialVersionUID = -78487475521731580L;

    @SuppressWarnings("ThrowableInstanceNeverThrown")
    static final ClosedSessionException INSTANCE = new ClosedSessionException();

    static {
        INSTANCE.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

    /**
     * Creates a new instance.
     */
    public ClosedSessionException() {}

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public ClosedSessionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public ClosedSessionException(String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public ClosedSessionException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new instance with the specified {@code message}, {@code cause}, suppression enabled or
     * disabled, and writable stack trace enabled or disabled.
     */
    protected ClosedSessionException(String message, Throwable cause, boolean enableSuppression,
                                     boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
