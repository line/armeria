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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", "1"})
    void testToBooleanTrue(String input) {
        assertThat(StringUtil.toBoolean(input)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "False", "0"})
    void testToBooleanFalse(String input) {
        assertThat(StringUtil.toBoolean(input)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tRUE", "FaLsE", "yes", "no", "TrUe", " false ", " true ", "", " 0 "})
    void testToBooleanInvalid(String input) {
        assertThatThrownBy(() -> StringUtil.toBoolean(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testToBooleanNull() {
        assertThatThrownBy(() -> StringUtil.toBoolean(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", "1"})
    void testToBooleanOrNullTrue(String input) {
        assertThat(StringUtil.toBooleanOrNull(input)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "False", "0"})
    void testToBooleanOrNullFalse(String input) {
        assertThat(StringUtil.toBooleanOrNull(input)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tRUE", "FaLsE", "yes", "no", "TrUe", " false ", " true ", "", " 0 "})
    void testToBooleanOrNullInvalid(String input) {
        assertThat(StringUtil.toBooleanOrNull(input)).isNull();
    }

    @Test
    void testToBooleanOrNullNull() {
        assertThat(StringUtil.toBooleanOrNull(null)).isNull();
    }
}
