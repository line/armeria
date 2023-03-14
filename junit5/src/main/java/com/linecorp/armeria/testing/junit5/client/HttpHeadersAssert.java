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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;

import io.netty.util.AsciiString;

/**
 * Assertion methods for HttpData
 */
public final class HttpHeadersAssert extends AssertThat<HttpHeaders, TestHttpResponse> {
    HttpHeadersAssert(HttpHeaders actual, TestHttpResponse back) {
        super(actual, back);
    }

    public TestHttpResponse isEqualTo(HttpHeaders expected) {
        requireNonNull(expected, "expected");
        checkState(actual().equals(expected), "\nexpected: %s\n but was: %s", expected, actual());
        return back();
    }

    public TestHttpResponse contentLengthIsEqualTo(long expected) {
        checkState(actual().contentLength() == expected, "\nexpected: %s\n but was: %s", expected,
                   actual().contentLength());
        return back();
    }

    public TestHttpResponse contentTypeIsEqualTo(MediaType expected) {
        requireNonNull(expected, "expected");
        checkState(actual().contentType().equals(expected), "\nexpected: %s\n but was: %s", expected,
                   actual().contentType());
        return back();
    }

    public TestHttpResponse contentDispositionIsEqualTo(ContentDisposition expected) {
        requireNonNull(expected, "expected");
        checkState(actual().contentDisposition().equals(expected), "\nexpected: %s\n but was: %s", expected,
                   actual().contentDisposition());
        return back();
    }

    public TestHttpResponse contains(CharSequence name) {
        requireNonNull(name, "name");
        checkState(actual().contains(name), "\nExpecting to contain %s but was not", name);
        return back();
    }

    public TestHttpResponse contains(CharSequence name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        checkState(actual().contains(name, value), "\nExpecting to contain %s: %s but was not", name, value);
        return back();
    }

    public TestHttpResponse containsObject(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        checkState(actual().containsObject(name, value), "\nExpecting to contain %s: %s but was not", name,
                   value);
        return back();
    }

    public TestHttpResponse containsBoolean(CharSequence name, boolean value) {
        requireNonNull(name, "name");
        checkState(actual().containsBoolean(name, value), "\nExpecting to contain %s: %s but was not", name,
                   value);
        return back();
    }

    public TestHttpResponse containsInt(CharSequence name, int value) {
        requireNonNull(name, "name");
        checkState(actual().containsInt(name, value), "\nExpecting to contain %s: %s but was not", name, value);
        return back();
    }

    public TestHttpResponse containsLong(CharSequence name, long value) {
        requireNonNull(name, "name");
        checkState(actual().containsLong(name, value), "\nExpecting to contain %s: %s but was not", name,
                   value);
        return back();
    }

    public TestHttpResponse containsFloat(CharSequence name, float value) {
        requireNonNull(name, "name");
        checkState(actual().containsFloat(name, value), "\nExpecting to contain %s: %s but was not", name,
                   value);
        return back();
    }

    public TestHttpResponse containsDouble(CharSequence name, double value) {
        requireNonNull(name, "name");
        checkState(actual().containsDouble(name, value), "\nExpecting to contain %s: %s but was not", name,
                   value);
        return back();
    }

    public TestHttpResponse containsTimeMillis(CharSequence name, long value) {
        requireNonNull(name, "name");
        checkState(actual().containsTimeMillis(name, value), "\nExpecting to contain %s: %s but was not", name,
                   value);
        return back();
    }

    public TestHttpResponse sizeIsEqualTo(int expected) {
        checkState(actual().size() == expected, "\nexpected: %s\n but was: %s", expected, actual().size());
        return back();
    }

    public TestHttpResponse isEmpty() {
        checkState(actual().isEmpty(), "\nExpecting empty but was not");
        return back();
    }

    public TestHttpResponse namesContains(AsciiString expected) {
        requireNonNull(expected, "expected");
        checkState(actual().names().contains(expected), "\nExpecting to contain %s but was not", expected);
        return back();
    }

    public TestHttpResponse namesDoesNotContains(AsciiString expected) {
        requireNonNull(expected, "expected");
        checkState(!actual().names().contains(expected), "\nExpecting to not contain %s but was",
                   expected);
        return back();
    }
}
