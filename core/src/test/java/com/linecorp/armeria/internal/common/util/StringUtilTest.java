/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StringUtilTest {
    @Test
    void intToString() {
        // cached
        for (int i = -1000; i < 1000; i++) {
            assertThat(StringUtil.toString(i)).isEqualTo(Integer.toString(i));
        }

        // non-cached
        assertThat(StringUtil.toString(-1001)).isEqualTo("-1001");
        assertThat(StringUtil.toString(1001)).isEqualTo("1001");
    }

    @Test
    void testToBoolean() {
        // Test supported boolean values
        assertThat(StringUtil.toBoolean("true")).isTrue();
        assertThat(StringUtil.toBoolean("TRUE")).isTrue();
        assertThat(StringUtil.toBoolean("True")).isTrue();
        assertThat(StringUtil.toBoolean("1")).isTrue();

        assertThat(StringUtil.toBoolean("false")).isFalse();
        assertThat(StringUtil.toBoolean("FALSE")).isFalse();
        assertThat(StringUtil.toBoolean("False")).isFalse();
        assertThat(StringUtil.toBoolean("0")).isFalse();
    }

    @Test
    void testToBooleanOrNull() {
        // Test supported boolean values
        assertThat(StringUtil.toBooleanOrNull("true")).isTrue();
        assertThat(StringUtil.toBooleanOrNull("TRUE")).isTrue();
        assertThat(StringUtil.toBooleanOrNull("True")).isTrue();
        assertThat(StringUtil.toBooleanOrNull("1")).isTrue();

        assertThat(StringUtil.toBooleanOrNull("false")).isFalse();
        assertThat(StringUtil.toBooleanOrNull("FALSE")).isFalse();
        assertThat(StringUtil.toBooleanOrNull("False")).isFalse();
        assertThat(StringUtil.toBooleanOrNull("0")).isFalse();

        // Test unsupported values return null
        assertThat(StringUtil.toBooleanOrNull("tRUE")).isNull();
        assertThat(StringUtil.toBooleanOrNull("FaLsE")).isNull();
        assertThat(StringUtil.toBooleanOrNull("invalid")).isNull();
    }
}
