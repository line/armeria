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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link CancellationException} raised when an inbound stream is cancelled or aborted and completes.
 */
@UnstableApi
public class InboundCompleteException extends CancellationException {
    private static final long serialVersionUID = -5231663021153944677L;

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public InboundCompleteException(@Nullable String message) {
        super(message);
    }

    /**
     * Creates a new instance with the specified {@code message} and {@code cause}.
     */
    public InboundCompleteException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
