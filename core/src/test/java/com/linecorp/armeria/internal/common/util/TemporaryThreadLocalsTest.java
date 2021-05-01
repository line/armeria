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
        TemporaryThreadLocals.get().clear();
    }

    @Test
    void byteArrayReuse() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final byte[] array = tempThreadLocals.byteArray(8);
        assertThat(array).hasSize(8);
        tempThreadLocals.releaseByteArray();
        for (int i = 0; i < 8; i++) {
            assertThat(tempThreadLocals.byteArray(i)).isSameAs(array);
            tempThreadLocals.releaseByteArray();
        }
    }

    @Test
    void byteArrayReallocation() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final byte[] array = tempThreadLocals.byteArray(8);
        assertThat(array).hasSize(8);
        tempThreadLocals.releaseByteArray();

        final byte[] newArray = tempThreadLocals.byteArray(9);
        assertThat(newArray).hasSize(9);
        tempThreadLocals.releaseByteArray();
    }

    @Test
    void tooLargeByteArray() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final byte[] largeArray = tempThreadLocals.byteArray(TemporaryThreadLocals.MAX_BYTE_ARRAY_CAPACITY + 1);
        assertThat(largeArray).hasSize(TemporaryThreadLocals.MAX_BYTE_ARRAY_CAPACITY + 1);

        // A large array should not be reused.
        final byte[] smallArray = tempThreadLocals.byteArray(8);
        assertThat(smallArray).hasSize(8);
        tempThreadLocals.releaseByteArray();
    }

    @Test
    void byteArrayReuseBeforeReleasing() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        tempThreadLocals.byteArray(8);
        assertThatThrownBy(() -> tempThreadLocals.byteArray(8))
                .isExactlyInstanceOf(IllegalStateException.class);
        tempThreadLocals.releaseByteArray();
    }

    @Test
    void charArrayReuse() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final char[] array = tempThreadLocals.charArray(8);
        assertThat(array).hasSize(8);
        tempThreadLocals.releaseCharArray();
        for (int i = 0; i < 8; i++) {
            assertThat(tempThreadLocals.charArray(i)).isSameAs(array);
            tempThreadLocals.releaseCharArray();
        }
    }

    @Test
    void charArrayReallocation() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final char[] array = tempThreadLocals.charArray(8);
        assertThat(array).hasSize(8);
        tempThreadLocals.releaseCharArray();

        final char[] newArray = tempThreadLocals.charArray(9);
        assertThat(newArray).hasSize(9);
        tempThreadLocals.releaseCharArray();
    }

    @Test
    void tooLargeCharArray() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final char[] largeArray = tempThreadLocals.charArray(TemporaryThreadLocals.MAX_CHAR_ARRAY_CAPACITY + 1);
        assertThat(largeArray).hasSize(TemporaryThreadLocals.MAX_CHAR_ARRAY_CAPACITY + 1);

        // A large array should not be reused.
        final char[] smallArray = tempThreadLocals.charArray(8);
        assertThat(smallArray).hasSize(8);
        tempThreadLocals.releaseCharArray();
    }

    @Test
    void charArrayReuseBeforeReleasing() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        tempThreadLocals.charArray(8);
        assertThatThrownBy(() -> tempThreadLocals.charArray(8))
                .isExactlyInstanceOf(IllegalStateException.class);
        tempThreadLocals.releaseCharArray();
    }

    @Test
    void intArrayReuse() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final int[] array = tempThreadLocals.intArray(8);
        assertThat(array).hasSize(8);
        tempThreadLocals.releaseIntArray();
        for (int i = 0; i < 8; i++) {
            assertThat(tempThreadLocals.intArray(i)).isSameAs(array);
            tempThreadLocals.releaseIntArray();
        }
    }

    @Test
    void intArrayReallocation() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final int[] array = tempThreadLocals.intArray(8);
        assertThat(array).hasSize(8);
        tempThreadLocals.releaseIntArray();

        final int[] newArray = tempThreadLocals.intArray(9);
        assertThat(newArray).hasSize(9);
        tempThreadLocals.releaseIntArray();
    }

    @Test
    void tooLargeIntArray() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final int[] largeArray = tempThreadLocals.intArray(TemporaryThreadLocals.MAX_INT_ARRAY_CAPACITY + 1);
        assertThat(largeArray).hasSize(TemporaryThreadLocals.MAX_INT_ARRAY_CAPACITY + 1);

        // A large array should not be reused.
        final int[] smallArray = tempThreadLocals.intArray(8);
        assertThat(smallArray).hasSize(8);
        tempThreadLocals.releaseIntArray();
    }

    @Test
    void intArrayReuseBeforeReleasing() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        tempThreadLocals.intArray(8);
        assertThatThrownBy(() -> tempThreadLocals.intArray(8))
                .isExactlyInstanceOf(IllegalStateException.class);
        tempThreadLocals.releaseIntArray();
    }

    @Test
    void stringBuilderReuse() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final StringBuilder buf1 = tempThreadLocals.stringBuilder();
        assertThat(buf1).isEmpty();
        buf1.append("foo");
        tempThreadLocals.releaseStringBuilder();

        final StringBuilder buf2 = tempThreadLocals.stringBuilder();
        assertThat(buf2).isEmpty();
        assertThat(buf2).isSameAs(buf1);
        tempThreadLocals.releaseStringBuilder();
    }

    @Test
    void tooLargeStringBuilder() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final StringBuilder buf1 = tempThreadLocals.stringBuilder();
        buf1.append(Strings.repeat("x", TemporaryThreadLocals.MAX_STRING_BUILDER_CAPACITY * 2));
        assertThat(buf1.capacity()).isGreaterThan(TemporaryThreadLocals.MAX_STRING_BUILDER_CAPACITY);
        tempThreadLocals.releaseStringBuilder();

        final StringBuilder buf2 = tempThreadLocals.stringBuilder();
        assertThat(buf2).isEmpty();
        assertThat(buf2.capacity()).isEqualTo(TemporaryThreadLocals.MAX_STRING_BUILDER_CAPACITY);
        assertThat(buf2).isNotSameAs(buf1);
        tempThreadLocals.releaseStringBuilder();
    }

    @Test
    void stringBuilderReuseBeforeReleasing() {
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        tempThreadLocals.stringBuilder();
        assertThatThrownBy(() -> tempThreadLocals.stringBuilder())
                .isExactlyInstanceOf(IllegalStateException.class);
        tempThreadLocals.releaseStringBuilder();
    }
}
