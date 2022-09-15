/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HttpJsonTranscodingOptionsBuilderTest {

    @Test
    void shouldNotDisableBothCamelAndProtoName() {
        assertThatThrownBy(() -> HttpJsonTranscodingOptions.builder()
                                                           .useCamelCaseQueryParams(false)
                                                           .useProtoFieldNameQueryParams(false)
                                                           .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Can't disable both useProtoFieldNameQueryParams and useCamelCaseQueryParams");
    }

    @Test
    void shouldReturnConfiguredSettings() {
        final HttpJsonTranscodingOptions withCamelCase =
                HttpJsonTranscodingOptions.builder()
                                          .useCamelCaseQueryParams(true)
                                          .build();
        assertThat(withCamelCase.useCamelCaseQueryParams()).isTrue();
        assertThat(withCamelCase.useProtoFieldNameQueryParams()).isTrue();

        final HttpJsonTranscodingOptions onlyCamelCase =
                HttpJsonTranscodingOptions.builder()
                                          .useCamelCaseQueryParams(true)
                                          .useProtoFieldNameQueryParams(false)
                                          .build();
        assertThat(onlyCamelCase.useCamelCaseQueryParams()).isTrue();
        assertThat(onlyCamelCase.useProtoFieldNameQueryParams()).isFalse();

        final HttpJsonTranscodingOptions onlyOriginalField =
                HttpJsonTranscodingOptions.builder()
                                          .useCamelCaseQueryParams(false)
                                          .useProtoFieldNameQueryParams(true)
                                          .build();
        assertThat(onlyOriginalField.useCamelCaseQueryParams()).isFalse();
        assertThat(onlyOriginalField.useProtoFieldNameQueryParams()).isTrue();

        final HttpJsonTranscodingOptions defaultOptions =
                HttpJsonTranscodingOptions.ofDefault();
        assertThat(defaultOptions.useCamelCaseQueryParams()).isFalse();
        assertThat(defaultOptions.useProtoFieldNameQueryParams()).isTrue();
    }
}
