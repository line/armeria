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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;

import com.linecorp.armeria.common.HttpData;

/**
 * Assertion methods for HttpData.
 */
public final class HttpDataAssert extends AbstractResponseAssert<HttpData> {
    HttpDataAssert(HttpData actual, TestHttpResponse response) {
        super(actual, response);
    }

    /**
     * Asserts that the actual {@link HttpData} is empty.
     */
    public TestHttpResponse isEmpty() {
        assertTrue(actual().isEmpty());
        return response();
    }

    /**
     * Asserts that the length of actual {@link HttpData} is equal to the given one.
     */
    public TestHttpResponse lengthIsEqualTo(int expected) {
        assertEquals(expected, actual().length());
        return response();
    }

    /**
     * Asserts that the {@link String} representation of actual {@link HttpData} is equal to the given one.
     */
    public TestHttpResponse stringIsEqualTo(Charset charset, String expected) {
        requireNonNull(charset, "charset");
        requireNonNull(expected, "expected");
        assertEquals(expected, actual().toString(charset));
        return response();
    }

    /**
     * Asserts that the UTF-8 {@link String} representation of actual {@link HttpData} is equal to the given
     * one.
     */
    public TestHttpResponse stringUtf8IsEqualTo(String expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual().toStringUtf8());
        return response();
    }

    /**
     * Asserts that the ascii {@link String} representation of actual {@link HttpData} is equal to the given
     * one.
     */
    public TestHttpResponse stringAsciiIsEqualTo(String expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual().toStringAscii());
        return response();
    }

    /**
     * Asserts that the {@link String} representation of actual {@link HttpData} contains the given value.
     */
    public TestHttpResponse stringContains(Charset charset, String expected) {
        requireNonNull(charset, "charset");
        requireNonNull(expected, "expected");
        assertTrue(actual().toString(charset).contains(expected));
        return response();
    }

    /**
     * Asserts that the UTF-8 {@link String} representation of actual {@link HttpData} contains the given
     * value.
     */
    public TestHttpResponse stringUtf8Contains(String expected) {
        requireNonNull(expected, "expected");
        assertTrue(actual().toStringUtf8().contains(expected));
        return response();
    }

    /**
     * Asserts that the ascii {@link String} representation of actual {@link HttpData} contains the given
     * value.
     */
    public TestHttpResponse stringAsciiContains(String expected) {
        requireNonNull(expected, "expected");
        assertTrue(actual().toStringAscii().contains(expected));
        return response();
    }

}
