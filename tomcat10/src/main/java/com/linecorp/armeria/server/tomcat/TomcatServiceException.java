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

package com.linecorp.armeria.server.tomcat;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link RuntimeException} that is raised when configuring or starting an embedded Tomcat fails.
 */
public final class TomcatServiceException extends RuntimeException {

    private static final long serialVersionUID = 8325593145475621692L;

    /**
     * Creates a new instance.
     */
    public TomcatServiceException() {}

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public TomcatServiceException(@Nullable String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public TomcatServiceException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public TomcatServiceException(@Nullable Throwable cause) {
        super(cause);
    }
}
