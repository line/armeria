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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.QueryParams;

class WebClientUtilTest {

    @Test
    void appendNullQueryParamsToPath() {
        assertThat(WebClientUtil.addQueryParams("foo", null)).isEqualTo("foo");
    }

    @CsvSource({
            "/world/test, /world/test?q2=foo",
            "/world/test?q1=foo, /world/test?q1=foo&q2=foo",
    })
    @ParameterizedTest
    void appendNonNullQueryParamsToPath(String path, String expected) {
        final QueryParams queryParams1 = QueryParams.builder()
                                                    .add("q2", "foo")
                                                    .build();
        final QueryParams queryParams2 = QueryParams.of();
        assertThat(WebClientUtil.addQueryParams(path, queryParams1)).isEqualTo(expected);
        assertThat(WebClientUtil.addQueryParams(path, queryParams2)).isEqualTo(path);
    }
}
