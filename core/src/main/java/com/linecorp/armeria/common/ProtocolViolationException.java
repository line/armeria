/*
 * Copyright 2016 LINE Corporation
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

/**
 * A {@link RuntimeException} raised when a remote peer violated the current {@link SessionProtocol}.
 */
public final class ProtocolViolationException extends RuntimeException {

    private static final long serialVersionUID = 4674394621849790490L;

    /**
     * Creates a new exception.
     */
    public ProtocolViolationException() {}

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public ProtocolViolationException(@Nullable String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public ProtocolViolationException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public ProtocolViolationException(@Nullable Throwable cause) {
        super(cause);
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            super.fillInStackTrace();
        }
        return this;
    }
}
