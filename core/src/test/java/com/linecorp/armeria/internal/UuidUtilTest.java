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

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidUtilTest {

    @Test
    void random() {
        final UUID uuid = UuidUtil.random();
        assertThat(uuid.variant()).isEqualTo(2); // IETF RFC 4122
        assertThat(uuid.version()).isEqualTo(4); // Randomly generated UUID
    }

    @Test
    void abbreviate() {
        assertThat(UuidUtil.abbreviate(new UUID(0x00000000FFFFFFFFL,
                                                0xFFFFFFFFFFFFFFFFL))).isEqualTo("00000000");
        assertThat(UuidUtil.abbreviate(new UUID(0xFFFFFFFF00000000L,
                                                0x0000000000000000L))).isEqualTo("ffffffff");
        assertThat(UuidUtil.abbreviate(new UUID(0x01234567FFFFFFFFL,
                                                0xFFFFFFFFFFFFFFFFL))).isEqualTo("01234567");
        assertThat(UuidUtil.abbreviate(new UUID(0x89ABCDEFFFFFFFFFL,
                                                0xFFFFFFFFFFFFFFFFL))).isEqualTo("89abcdef");
    }
}
