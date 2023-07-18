/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.testing.junit5.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertion methods for Throwable.
 */
public final class ThrowableAssert {
    private final Throwable cause;

    ThrowableAssert(Throwable cause) {
        this.cause = cause;
    }

    /**
     * Verifies that the actual {@link Throwable} value is an instance of the given type.
     * The {@code expectedType} cannot be null.
     */
    public ThrowableAssert isInstanceOf(Class<? extends Throwable> expectedType) {
        requireNonNull(expectedType);
        assertInstanceOf(expectedType, cause);
        return this;
    }

    /**
     * Verifies that the message of the actual {@link Throwable} is equal to the given message.
     * The {@code message} cannot be null.
     */
    public ThrowableAssert hasMessage(String message) {
        assertEquals(message, cause.getMessage());
        return this;
    }

    /**
     * Verifies that the message of the actual {@link Throwable} starts with the given message.
     * The {@code message} cannot be null.
     */
    public ThrowableAssert hasMessageStartingWith(String message) {
        requireNonNull(message, "message");
        assertNotNull(cause.getMessage());
        assertTrue(cause.getMessage().startsWith(message));
        return this;
    }

    /**
     * Verifies that the message of the actual {@link Throwable} contains the given message.
     * The {@code message} cannot be null.
     */
    public ThrowableAssert hasMessageContaining(String message) {
        requireNonNull(message, "message");
        assertNotNull(cause.getMessage());
        assertTrue(cause.getMessage().contains(message));
        return this;
    }

    /**
     * Verifies that the message of the actual {@link Throwable} does not contain the given message or is null.
     * The {@code message} cannot be null.
     */
    public ThrowableAssert hasMessageNotContaining(String message) {
        requireNonNull(message, "message");
        if (cause.getMessage() == null) {
            return this;
        }
        assertFalse(cause.getMessage().contains(message));
        return this;
    }

    /**
     * Verifies that the message of the actual {@link Throwable} ends with the given message.
     * The {@code message} cannot be null.
     */
    public ThrowableAssert hasMessageEndingWith(String message) {
        requireNonNull(message, "message");
        assertNotNull(cause.getMessage());
        assertTrue(cause.getMessage().endsWith(message));
        return this;
    }
}
