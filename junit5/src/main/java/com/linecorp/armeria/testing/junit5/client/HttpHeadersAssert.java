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
 * Assertion methods for HttpData
 */
public final class HttpHeadersAssert extends AbstractResponseAssert<HttpHeaders> {
    HttpHeadersAssert(HttpHeaders actual, TestHttpResponse response) {
        super(actual, response);
    }

    public TestHttpResponse isEqualTo(HttpHeaders expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual());
        return response();
    }

    public TestHttpResponse contentLengthIsEqualTo(long expected) {
        assertEquals(expected, actual().contentLength());
        return response();
    }

    public TestHttpResponse contentTypeIsEqualTo(MediaType expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual().contentType());
        return response();
    }

    public TestHttpResponse contentDispositionIsEqualTo(ContentDisposition expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual().contentDisposition());
        return response();
    }

    public TestHttpResponse contains(CharSequence name) {
        requireNonNull(name, "name");
        assertTrue(actual().contains(name));
        return response();
    }

    public TestHttpResponse contains(CharSequence name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        assertTrue(actual().contains(name, value));
        return response();
    }

    public TestHttpResponse containsObject(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        assertTrue(actual().containsObject(name, value));
        return response();
    }

    public TestHttpResponse containsBoolean(CharSequence name, boolean value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsBoolean(name, value));
        return response();
    }

    public TestHttpResponse containsInt(CharSequence name, int value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsInt(name, value));
        return response();
    }

    public TestHttpResponse containsLong(CharSequence name, long value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsLong(name, value));
        return response();
    }

    public TestHttpResponse containsFloat(CharSequence name, float value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsFloat(name, value));
        return response();
    }

    public TestHttpResponse containsDouble(CharSequence name, double value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsDouble(name, value));
        return response();
    }

    public TestHttpResponse containsTimeMillis(CharSequence name, long value) {
        requireNonNull(name, "name");
        assertTrue(actual().containsTimeMillis(name, value));
        return response();
    }

    public TestHttpResponse sizeIsEqualTo(int expected) {
        assertEquals(expected, actual().size());
        return response();
    }

    public TestHttpResponse isEmpty() {
        assertTrue(actual().isEmpty());
        return response();
    }

    public TestHttpResponse namesContains(AsciiString expected) {
        requireNonNull(expected, "expected");
        assertTrue(actual().names().contains(expected));
        return response();
    }

    public TestHttpResponse namesDoesNotContains(AsciiString expected) {
        requireNonNull(expected, "expected");
        assertFalse(actual().names().contains(expected));
        return response();
    }
}
