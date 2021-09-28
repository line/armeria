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

package com.linecorp.armeria.common.stream;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link RuntimeException} that is raised when a {@link StreamMessage} has been closed unexpectedly.
 */
public class ClosedStreamException extends RuntimeException {

    private static final long serialVersionUID = -7665826869012452735L;

    private static final ClosedStreamException INSTANCE = new ClosedStreamException(null, null, false, false);

    /**
     * Returns a {@link ClosedStreamException} which may be a singleton or a new instance, depending on
     * {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public static ClosedStreamException get() {
        return isSampled() ? new ClosedStreamException(null, null, true, true) : INSTANCE;
    }

    private static boolean isSampled() {
        return Flags.verboseExceptionSampler().isSampled(ClosedStreamException.class);
    }

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public ClosedStreamException(@Nullable String message) {
        this(message, null, true, isSampled());
    }

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public ClosedStreamException(@Nullable String message, @Nullable Throwable cause) {
        this(message, cause, true, isSampled());
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public ClosedStreamException(@Nullable Throwable cause) {
        this(null, cause, true, isSampled());
    }

    /**
     * Creates a new instance with the specified {@code message}, {@code cause}, suppression enabled or
     * disabled, and writable stack trace enabled or disabled.
     */
    protected ClosedStreamException(@Nullable String message, @Nullable Throwable cause,
                                    boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
