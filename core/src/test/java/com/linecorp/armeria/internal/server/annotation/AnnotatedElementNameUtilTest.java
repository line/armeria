/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.server.annotation.AnnotatedElementNameUtil.toHeaderName;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class AnnotatedElementNameUtilTest {
    @Test
    public void ofHeaderName() {
        assertThat(toHeaderName("camelCase")).isEqualTo("camel-case");
        assertThat(toHeaderName("CamelCase")).isEqualTo("camel-case");
        assertThat(toHeaderName("snake_case")).isEqualTo("snake-case");
        assertThat(toHeaderName("SNAKE_CASE")).isEqualTo("snake-case");
        assertThat(toHeaderName("lowercase")).isEqualTo("lowercase");
        assertThat(toHeaderName("UPPERCASE")).isEqualTo("uppercase");

        // Just converted to lower case.
        assertThat(toHeaderName("What_Case")).isEqualTo("what_case");
    }
}
