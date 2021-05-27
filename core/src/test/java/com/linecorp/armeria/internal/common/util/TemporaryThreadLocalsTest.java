/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.base.Strings;

class TemporaryThreadLocalsTest {

    @BeforeEach
    void clear() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            tempThreadLocals.clear();
        }
    }

    @Test
    void byteArrayReuse() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final byte[] array = tempThreadLocals.byteArray(8);
            assertThat(array).hasSize(8);
            for (int i = 0; i < 8; i++) {
                assertThat(tempThreadLocals.byteArray(i)).isSameAs(array);
            }
        }
    }

    @Test
    void byteArrayReallocation() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final byte[] array = tempThreadLocals.byteArray(8);
            assertThat(array).hasSize(8);

            final byte[] newArray = tempThreadLocals.byteArray(9);
            assertThat(newArray).hasSize(9);
        }
    }

    @Test
    void tooLargeByteArray() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final byte[] largeArray = tempThreadLocals.byteArray(
                    TemporaryThreadLocals.MAX_BYTE_ARRAY_CAPACITY + 1);
            assertThat(largeArray).hasSize(TemporaryThreadLocals.MAX_BYTE_ARRAY_CAPACITY + 1);

            // A large array should not be reused.
            final byte[] smallArray = tempThreadLocals.byteArray(8);
            assertThat(smallArray).hasSize(8);
        }
    }

    @Test
    void charArrayReuse() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final char[] array = tempThreadLocals.charArray(8);
            assertThat(array).hasSize(8);
            for (int i = 0; i < 8; i++) {
                assertThat(tempThreadLocals.charArray(i)).isSameAs(array);
            }
        }
    }

    @Test
    void charArrayReallocation() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final char[] array = tempThreadLocals.charArray(8);
            assertThat(array).hasSize(8);

            final char[] newArray = tempThreadLocals.charArray(9);
            assertThat(newArray).hasSize(9);
        }
    }

    @Test
    void tooLargeCharArray() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final char[] largeArray = tempThreadLocals.charArray(
                    TemporaryThreadLocals.MAX_CHAR_ARRAY_CAPACITY + 1);
            assertThat(largeArray).hasSize(TemporaryThreadLocals.MAX_CHAR_ARRAY_CAPACITY + 1);

            // A large array should not be reused.
            final char[] smallArray = tempThreadLocals.charArray(8);
            assertThat(smallArray).hasSize(8);
        }
    }

    @Test
    void intArrayReuse() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final int[] array = tempThreadLocals.intArray(8);
            assertThat(array).hasSize(8);
            for (int i = 0; i < 8; i++) {
                assertThat(tempThreadLocals.intArray(i)).isSameAs(array);
            }
        }
    }

    @Test
    void intArrayReallocation() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final int[] array = tempThreadLocals.intArray(8);
            assertThat(array).hasSize(8);

            final int[] newArray = tempThreadLocals.intArray(9);
            assertThat(newArray).hasSize(9);
        }
    }

    @Test
    void tooLargeIntArray() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final int[] largeArray = tempThreadLocals.intArray(
                    TemporaryThreadLocals.MAX_INT_ARRAY_CAPACITY + 1);
            assertThat(largeArray).hasSize(TemporaryThreadLocals.MAX_INT_ARRAY_CAPACITY + 1);

            // A large array should not be reused.
            final int[] smallArray = tempThreadLocals.intArray(8);
            assertThat(smallArray).hasSize(8);
        }
    }

    @Test
    void stringBuilderReuse() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf1 = tempThreadLocals.stringBuilder();
            assertThat(buf1).isEmpty();
            buf1.append("foo");

            final StringBuilder buf2 = tempThreadLocals.stringBuilder();
            assertThat(buf2).isEmpty();
            assertThat(buf2).isSameAs(buf1);
        }
    }

    @Test
    void tooLargeStringBuilder() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf1 = tempThreadLocals.stringBuilder();
            buf1.append(Strings.repeat("x", TemporaryThreadLocals.MAX_STRING_BUILDER_CAPACITY * 2));
            assertThat(buf1.capacity()).isGreaterThan(TemporaryThreadLocals.MAX_STRING_BUILDER_CAPACITY);

            final StringBuilder buf2 = tempThreadLocals.stringBuilder();
            assertThat(buf2).isEmpty();
            assertThat(buf2.capacity()).isEqualTo(TemporaryThreadLocals.MAX_STRING_BUILDER_CAPACITY);
            assertThat(buf2).isNotSameAs(buf1);
        }
    }

    @Test
    void reuseBeforeReleasing() {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            tempThreadLocals.byteArray(8);
            tempThreadLocals.charArray(8);
            tempThreadLocals.intArray(8);
            tempThreadLocals.stringBuilder();
            assertThatThrownBy(TemporaryThreadLocals::acquire).isExactlyInstanceOf(IllegalStateException.class);
        }
    }
}
