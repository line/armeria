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

import java.nio.charset.Charset;
import java.util.Arrays;

import com.linecorp.armeria.common.HttpData;

/**
 * Assertion methods for HttpData
 */
public final class HttpDataAssert extends AssertThat<HttpData, TestHttpResponse> {
    HttpDataAssert(HttpData actual, TestHttpResponse back) {
        super(actual, back);
    }

    public TestHttpResponse isEqualTo(HttpData expected) {
        checkState(actual().equals(expected), "\nexpected: %s\n but was: %s", expected, actual());
        return back();
    }

    public TestHttpResponse isEmpty() {
        checkState(actual().equals(HttpData.empty()), "\nExpecting empty but was not");
        return back();
    }

    public TestHttpResponse arrayContains(byte... expected) {
        checkState(bytesContains(actual().array(), expected), "\nExpecting to contain %s but was not", Arrays.toString(expected));
        return back();
    }

    public TestHttpResponse arrayContains(Byte[] expected) {
        checkState(bytesContains(actual().array(), toPrimitiveByteArray(expected)), "\nExpecting to contain %s but was not", Arrays.toString(expected));
        return back();
    }

    public TestHttpResponse arrayContains(int... expected) {
        checkState(bytesContains(actual().array(), toByteArray(expected)), "\nExpecting to contain %s but was not", Arrays.toString(expected));
        return back();
    }

    public TestHttpResponse arrayContainsExactly(byte... expected) {
        checkState(bytesContainsExactly(actual().array(), expected), "\nExpecting to contain %s but was not", Arrays.toString(expected));
        return back();
    }

    public TestHttpResponse arrayContainsExactly(Byte[] expected) {
        checkState(bytesContainsExactly(actual().array(), toPrimitiveByteArray(expected)), "\nExpecting to contain %s but was not", Arrays.toString(expected));
        return back();
    }

    public TestHttpResponse arrayContainsExactly(int... expected) {
        checkState(bytesContainsExactly(actual().array(), toByteArray(expected)), "\nExpecting to contain %s but was not", Arrays.toString(expected));
        return back();
    }

    public TestHttpResponse lengthIsEqualTo(int expected) {
        checkState(actual().length() == expected, "\nexpected: %s\n but was: %s", expected, actual().length());
        return back();
    }

    public TestHttpResponse stringIsEqualTo(Charset charset, String expected) {
        checkState(actual().toString(charset).equals(expected), "\nexpected: %s\n but was: %s", expected,
                   actual().toString(charset));
        return back();
    }

    public TestHttpResponse stringUtf8IsEqualTo(String expected) {
        checkState(actual().toStringUtf8().equals(expected), "\nexpected: %s\n but was: %s", expected,
                   actual().toStringUtf8());
        return back();
    }

    public TestHttpResponse stringAsciiIsEqualTo(String expected) {
        checkState(actual().toStringAscii().equals(expected), "\nexpected: %s\n but was: %s", expected,
                   actual().toStringAscii());
        return back();
    }

    public TestHttpResponse stringContains(Charset charset, String expected) {
        checkState(actual().toString(charset).contains(expected), "\nExpecting to contain %s but was not", expected);
        return back();
    }

    public TestHttpResponse stringUtf8Contains(String expected) {
        checkState(actual().toStringUtf8().contains(expected), "\nExpecting to contain %s but was not", expected);
        return back();
    }

    public TestHttpResponse stringAsciiContains(String expected) {
        checkState(actual().toStringAscii().contains(expected), "\nExpecting to contain %s but was not", expected);
        return back();
    }

    private boolean bytesContains(byte[] bytes, byte[] expected) {
        for (byte e : expected) {
            boolean contains = false;
            for (byte b : bytes) {
                if (b == e) {
                    contains = true;
                    break;
                }
            }
            if (!contains) {
                return false;
            }
        }
        return true;
    }

    private boolean bytesContainsExactly(byte[] bytes, byte[] expected) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] toByteArray(int[] ints) {
        if (ints == null) {
            return null;
        }
        final byte[] bytes = new byte[ints.length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ints[i];
        }
        return bytes;
    }

    private static byte[] toPrimitiveByteArray(Byte[] bytes) {
        final byte[] primitives = new byte[bytes.length];
        for (int i = 0; i < primitives.length; i++) {
            primitives[i] = bytes[i];
        }
        return primitives;
    }
}
