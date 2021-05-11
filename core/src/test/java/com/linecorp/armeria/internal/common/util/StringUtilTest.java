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
}
