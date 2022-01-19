/*
 * Copyright 2022 LINE Corporation
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
 * A {@link RuntimeException} that is raised when attempted to access a null request or response content.
 */
@UnstableApi
public final class NoHttpContentException extends RuntimeException {

    private static final long serialVersionUID = 8264452803561735504L;

    /**
     * Creates a new instance.
     */
    public NoHttpContentException() {}

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public NoHttpContentException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public NoHttpContentException(@Nullable String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public NoHttpContentException(@Nullable Throwable cause) {
        super(cause);
    }
}
