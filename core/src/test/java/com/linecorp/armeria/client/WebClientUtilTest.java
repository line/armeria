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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.QueryParams;

class WebClientUtilTest {

    @Test
    void testFormingPathWithNullQueryParams() {
        assertThatNullPointerException().isThrownBy(() -> WebClientUtil.addQueryParams("", null));
    }

    @Test
    @SuppressWarnings("checkstyle:RegexpMultiline")
    void testFormingPathWithNonNullQueryParams() {
        final QueryParams queryParams1 = QueryParams.builder()
                                                    .add("q2", "foo")
                                                    .build();
        final QueryParams queryParams2 = QueryParams.builder()
                                                    .build();

        final QueryParams[] queryParams = new QueryParams[] {
                queryParams1, queryParams2,
                queryParams1, queryParams2
        };
        final String[] paths = new String[] {
                "/world/test", "/world/test",
                "/world/test?q1=foo", "/world/test?q1=foo"
        };
        final String[] expectedPaths = new String[] {
                "/world/test?q2=foo", "/world/test",
                "/world/test?q1=foo&q2=foo", "/world/test?q1=foo"
        };

        for (int idx = 0; idx < queryParams.length; idx++) {
            assertThat(WebClientUtil.addQueryParams(paths[idx], queryParams[idx]))
                    .isEqualTo(expectedPaths[idx]);
        }
    }
}
