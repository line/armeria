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

package com.linecorp.armeria.client.endpoint;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link RuntimeException} raised when the resolution of an {@link EndpointGroup} fails.
 */
public class EndpointGroupException extends RuntimeException {

    private static final long serialVersionUID = -1027414271521488397L;

    /**
     * Creates a new instance.
     */
    public EndpointGroupException() {}

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public EndpointGroupException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public EndpointGroupException(@Nullable String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public EndpointGroupException(@Nullable Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new instance with the specified {@code message}, {@code cause}, suppression enabled or
     * disabled, and writable stack trace enabled or disabled.
     */
    protected EndpointGroupException(@Nullable String message, @Nullable Throwable cause,
                                     boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
