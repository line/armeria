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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertion methods for Throwable.
 */
public final class ThrowableAssert {
    private final Throwable cause;

    ThrowableAssert(Throwable cause) {
        requireNonNull(cause, "cause");
        this.cause = cause;
    }

    /**
     * Asserts that the actual {@link Throwable} is an instance of the given type.
     * The {@code expectedType} cannot be null.
     */
    public ThrowableAssert isInstanceOf(Class<? extends Throwable> expectedType) {
        requireNonNull(expectedType, "expectedType");
        assertInstanceOf(expectedType, cause);
        return this;
    }

    /**
     * Returns a new assertion object that uses the cause of the current Throwable as the actual Throwable.
     */
    public ThrowableAssert cause() {
        final Throwable cause0 = cause.getCause();
        assertNotNull(cause0);
        return new ThrowableAssert(cause0);
    }

    /**
     * Returns a new assertion object that uses the root cause of the current Throwable as the actual Throwable.
     */
    public ThrowableAssert rootCause() {
        Throwable root = cause.getCause();
        assertNotNull(root);
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return new ThrowableAssert(root);
    }

    /**
     * Asserts that the actual {@link Throwable} does not have a cause.
     */
    public ThrowableAssert hasNoCause() {
        assertNull(cause.getCause());
        return this;
    }

    /**
     * Asserts that the message of the actual {@link Throwable} is equal to the given message.
     * The {@code message} cannot be null.
     */
    public ThrowableAssert hasMessage(String message) {
        requireNonNull(message, "message");
        assertEquals(message, cause.getMessage());
        return this;
    }

    /**
     * Asserts that the actual {@link Throwable} does not have a message.
     */
    public ThrowableAssert hasNoMessage() {
        assertNull(cause.getMessage());
        return this;
    }

    /**
     * Asserts that the message of the actual {@link Throwable} starts with the given message.
     * The {@code message} cannot be null.
     */
    public ThrowableAssert hasMessageStartingWith(String message) {
        requireNonNull(message, "message");
        assertNotNull(cause.getMessage());
        assertTrue(cause.getMessage().startsWith(message));
        return this;
    }

    /**
     * Asserts that the message of the actual {@link Throwable} contains the given message.
     * The {@code message} cannot be null.
     */
    public ThrowableAssert hasMessageContaining(String message) {
        requireNonNull(message, "message");
        assertNotNull(cause.getMessage());
        assertTrue(cause.getMessage().contains(message));
        return this;
    }

    /**
     * Asserts that the message of the actual {@link Throwable} does not contain the given message or is null.
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
     * Asserts that the message of the actual {@link Throwable} ends with the given message.
     * The {@code message} cannot be null.
     */
    public ThrowableAssert hasMessageEndingWith(String message) {
        requireNonNull(message, "message");
        assertNotNull(cause.getMessage());
        assertTrue(cause.getMessage().endsWith(message));
        return this;
    }
}
