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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;

import io.netty.util.AsciiString;

/**
 * Assertion methods for HttpData.
 */
public final class HttpHeadersAssert extends AbstractResponseAssert<HttpHeaders> {
    HttpHeadersAssert(HttpHeaders actual, TestHttpResponse response) {
        super(actual, response);
    }

    /**
     * Verifies that the actual {@link HttpHeaders} is equal to the given one.
     * The {@code expected} cannot be null.
     */
    public TestHttpResponse isEqualTo(HttpHeaders expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual());
        return response();
    }

    /**
     * Verifies that the content length of actual {@link HttpHeaders} is equal to the given one.
     */
    public TestHttpResponse contentLengthIsEqualTo(long expected) {
        assertEquals(expected, actual().contentLength());
        return response();
    }

    /**
     * Verifies that the content type of actual {@link HttpHeaders} is equal to the given one.
     * The {@code expected} cannot be null.
     */
    public TestHttpResponse contentTypeIsEqualTo(MediaType expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual().contentType());
        return response();
    }

    /**
     * Verifies that the content disposition of actual {@link HttpHeaders} is equal to the given one.
     * The {@code expected} cannot be null.
     */
    public TestHttpResponse contentDispositionIsEqualTo(ContentDisposition expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual().contentDisposition());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} contains the given name.
     * The {@code name} cannot be null.
     */
    public TestHttpResponse contains(CharSequence name) {
        requireNonNull(name, "name");
        assertTrue(actual().contains(name));
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} contains the given name and {@link String} value.
     * The {@code name} and the {@code value} cannot be null.
     */
    public TestHttpResponse contains(CharSequence name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        assertTrue(actual().contains(name, value));
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} contains the given name and {@link Object} value.
     * The {@code name} and the {@code value} cannot be null.
     */
    public TestHttpResponse containsObject(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        assertTrue(actual().containsObject(name, value));
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} contains the given name and boolean value.
     * The {@code name} cannot be null.
     */
    public TestHttpResponse containsBoolean(CharSequence name, boolean value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsBoolean(name, value));
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} contains the given name and int value.
     * The {@code name} cannot be null.
     */
    public TestHttpResponse containsInt(CharSequence name, int value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsInt(name, value));
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} contains the given name and long value.
     * The {@code name} cannot be null.
     */
    public TestHttpResponse containsLong(CharSequence name, long value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsLong(name, value));
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} contains the given name and float value.
     * The {@code name} cannot be null.
     */
    public TestHttpResponse containsFloat(CharSequence name, float value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsFloat(name, value));
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} contains the given name and double value.
     * The {@code name} cannot be null.
     */
    public TestHttpResponse containsDouble(CharSequence name, double value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsDouble(name, value));
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} contains the given name and time(millis) value.
     * The {@code name} cannot be null.
     */
    public TestHttpResponse containsTimeMillis(CharSequence name, long value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsTimeMillis(name, value));
        return response();
    }

    /**
     * Verifies that the size of actual {@link HttpHeaders} is equal to the given one.
     */
    public TestHttpResponse sizeIsEqualTo(int expected) {
        assertEquals(expected, actual().size());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpHeaders} is empty.
     */
    public TestHttpResponse isEmpty() {
        assertTrue(actual().isEmpty());
        return response();
    }

    /**
     * Verifies that the names of actual {@link HttpHeaders} contains the given ascii string.
     * The {@code expected} cannot be null.
     */
    public TestHttpResponse namesContains(AsciiString expected) {
        requireNonNull(expected, "expected");
        assertTrue(actual().names().contains(expected));
        return response();
    }

    /**
     * Verifies that the names of actual {@link HttpHeaders} does not contain the given ascii string.
     * The {@code expected} cannot be null.
     */
    public TestHttpResponse namesDoesNotContains(AsciiString expected) {
        requireNonNull(expected, "expected");
        assertFalse(actual().names().contains(expected));
        return response();
    }
}
