/*
 * Copyright 2025 LINE Corporation
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

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An {@link RuntimeException} raised when an IP address is rejected by a filter.
 */
@UnstableApi
public final class IpAddressRejectedException extends RuntimeException {

    private static final long serialVersionUID = 6649194857041445303L;

    /**
     * Creates a new instance.
     */
    public IpAddressRejectedException() {}

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public IpAddressRejectedException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public IpAddressRejectedException(@Nullable String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code cause}.
     */
    public IpAddressRejectedException(@Nullable Throwable cause) {
        super(cause);
    }
}
