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
package com.linecorp.armeria.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThreadLocalByteArrayTest {

    @BeforeEach
    void clear() {
        ThreadLocalByteArray.threadLocalByteArray.remove();
    }

    @Test
    void reuse() {
        final byte[] array = ThreadLocalByteArray.get(8);
        assertThat(array).hasSize(8);
        for (int i = 0; i < 8; i++) {
            assertThat(ThreadLocalByteArray.get(i)).isSameAs(array);
        }
    }

    @Test
    void reallocation() {
        final byte[] array = ThreadLocalByteArray.get(8);
        assertThat(array).hasSize(8);
        final byte[] newArray = ThreadLocalByteArray.get(9);
        assertThat(newArray).hasSize(9);
    }

    @Test
    void tooLargeArray() {
        final byte[] largeArray = ThreadLocalByteArray.get(ThreadLocalByteArray.MAX_CAPACITY + 1);
        assertThat(largeArray).hasSize(ThreadLocalByteArray.MAX_CAPACITY + 1);

        // A large array should not be reused.
        final byte[] smallArray = ThreadLocalByteArray.get(8);
        assertThat(smallArray).hasSize(8);
    }
}
