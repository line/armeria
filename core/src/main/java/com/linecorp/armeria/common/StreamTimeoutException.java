/*
 * Copyright 2024 LINE Corporation
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

import java.time.Duration;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * A {@link TimeoutException} raised when a stream operation exceeds the configured timeout.
 *
 * @see StreamMessage#timeout(Duration)
 */
public final class StreamTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 7585558758307122722L;

    /**
     * Creates a new instance with the specified {@code message}.
     */
    public StreamTimeoutException(@Nullable String message) {
        super(message);
    }
}
