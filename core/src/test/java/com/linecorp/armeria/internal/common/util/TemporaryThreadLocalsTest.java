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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.base.Strings;

class TemporaryThreadLocalsTest {

    @BeforeEach
    void clear() {
        TemporaryThreadLocals.get().clear();
    }

    @Test
    void byteArrayReuse() {
        final byte[] array = TemporaryThreadLocals.get().byteArray(8);
        assertThat(array).hasSize(8);
        for (int i = 0; i < 8; i++) {
            assertThat(TemporaryThreadLocals.get().byteArray(i)).isSameAs(array);
        }
    }

    @Test
    void byteArrayReallocation() {
        final byte[] array = TemporaryThreadLocals.get().byteArray(8);
        assertThat(array).hasSize(8);
        final byte[] newArray = TemporaryThreadLocals.get().byteArray(9);
        assertThat(newArray).hasSize(9);
    }

    @Test
    void tooLargeByteArray() {
        final byte[] largeArray =
                TemporaryThreadLocals.get().byteArray(TemporaryThreadLocals.MAX_BYTE_ARRAY_CAPACITY + 1);
        assertThat(largeArray).hasSize(TemporaryThreadLocals.MAX_BYTE_ARRAY_CAPACITY + 1);

        // A large array should not be reused.
        final byte[] smallArray = TemporaryThreadLocals.get().byteArray(8);
        assertThat(smallArray).hasSize(8);
    }

    @Test
    void charArrayReuse() {
        final char[] array = TemporaryThreadLocals.get().charArray(8);
        assertThat(array).hasSize(8);
        for (int i = 0; i < 8; i++) {
            assertThat(TemporaryThreadLocals.get().charArray(i)).isSameAs(array);
        }
    }

    @Test
    void charArrayReallocation() {
        final char[] array = TemporaryThreadLocals.get().charArray(8);
        assertThat(array).hasSize(8);
        final char[] newArray = TemporaryThreadLocals.get().charArray(9);
        assertThat(newArray).hasSize(9);
    }

    @Test
    void tooLargeCharArray() {
        final char[] largeArray =
                TemporaryThreadLocals.get().charArray(TemporaryThreadLocals.MAX_CHAR_ARRAY_CAPACITY + 1);
        assertThat(largeArray).hasSize(TemporaryThreadLocals.MAX_CHAR_ARRAY_CAPACITY + 1);

        // A large array should not be reused.
        final char[] smallArray = TemporaryThreadLocals.get().charArray(8);
        assertThat(smallArray).hasSize(8);
    }

    @Test
    void stringBuilderReuse() {
        final StringBuilder buf1 = TemporaryThreadLocals.get().stringBuilder();
        assertThat(buf1).isEmpty();
        buf1.append("foo");

        final StringBuilder buf2 = TemporaryThreadLocals.get().stringBuilder();
        assertThat(buf2).isEmpty();
        assertThat(buf2).isSameAs(buf1);
    }

    @Test
    void tooLargeStringBuilder() {
        final StringBuilder buf1 = TemporaryThreadLocals.get().stringBuilder();
        buf1.append(Strings.repeat("x", TemporaryThreadLocals.MAX_STRING_BUILDER_CAPACITY * 2));
        assertThat(buf1.capacity()).isGreaterThan(TemporaryThreadLocals.MAX_STRING_BUILDER_CAPACITY);

        final StringBuilder buf2 = TemporaryThreadLocals.get().stringBuilder();
        assertThat(buf2).isEmpty();
        assertThat(buf2.capacity()).isEqualTo(TemporaryThreadLocals.MAX_STRING_BUILDER_CAPACITY);
        assertThat(buf2).isNotSameAs(buf1);
    }
}
