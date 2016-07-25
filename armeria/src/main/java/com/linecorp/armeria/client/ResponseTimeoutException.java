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

import com.linecorp.armeria.common.TimeoutException;

/**
 * A {@link TimeoutException} raised when a response has not been received from a server within timeout.
 */
public class ResponseTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 2556616197251937869L;

    /**
     * Creates a new instance.
     */
    public ResponseTimeoutException() {}

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public ResponseTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public ResponseTimeoutException(String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public ResponseTimeoutException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new instance with the specified {@code message}, {@code cause}, suppression enabled or
     * disabled, and writable stack trace enabled or disabled.
     */
    protected ResponseTimeoutException(String message, Throwable cause, boolean enableSuppression,
                                       boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
