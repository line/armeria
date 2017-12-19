/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.Test;

public class HttpParametersTest {

    @Test
    public void caseSensitive() {
        HttpParameters p = HttpParameters.of();
        p.add("abc", "abc1");
        p.add("abc", "abc2");
        p.add("ABC", "ABC");

        assertThat(p.size()).isEqualTo(3);

        List<String> values = p.getAll("abc");
        assertThat(values.size()).isEqualTo(2);
        assertThat(values.get(0)).isEqualTo("abc1");
        assertThat(values.get(1)).isEqualTo("abc2");

        assertThat(p.get("abc")).isEqualTo("abc1");
        assertThat(p.get("ABC")).isEqualTo("ABC");
    }
}
