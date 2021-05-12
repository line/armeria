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
 * under the License.
 */
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HttpMethodTest {

    @Test
    void tryParse() {
        assertThat(HttpMethod.tryParse("OPTIONS")).isSameAs(HttpMethod.OPTIONS);
        assertThat(HttpMethod.tryParse("GET")).isSameAs(HttpMethod.GET);
        assertThat(HttpMethod.tryParse("HEAD")).isSameAs(HttpMethod.HEAD);
        assertThat(HttpMethod.tryParse("POST")).isSameAs(HttpMethod.POST);
        assertThat(HttpMethod.tryParse("PUT")).isSameAs(HttpMethod.PUT);
        assertThat(HttpMethod.tryParse("PATCH")).isSameAs(HttpMethod.PATCH);
        assertThat(HttpMethod.tryParse("DELETE")).isSameAs(HttpMethod.DELETE);
        assertThat(HttpMethod.tryParse("TRACE")).isSameAs(HttpMethod.TRACE);
        assertThat(HttpMethod.tryParse("CONNECT")).isSameAs(HttpMethod.CONNECT);

        // Should ignore UNKNOWN.
        assertThat(HttpMethod.tryParse("UNKNOWN")).isNull();
        // Should be case-sensitive.
        assertThat(HttpMethod.tryParse("options")).isNull();
        // Should ignore anything else.
        assertThat(HttpMethod.tryParse("foo")).isNull();
        assertThat(HttpMethod.tryParse(null)).isNull();
    }
}
