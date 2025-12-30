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

import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule.JSON_NAME;
import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule.LOWER_CAMEL_CASE;
import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule.ORIGINAL_FIELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.api.HttpRule;
import com.google.common.collect.ImmutableList;

class HttpJsonTranscodingOptionsBuilderTest {

    @Test
    void shouldDisallowEmptyNaming() {
        assertThatThrownBy(() -> HttpJsonTranscodingOptions.builder()
                                                           .queryParamMatchRules(ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Can't set an empty queryParamMatchRules");
    }

    @Test
    void shouldReturnConfiguredSettings() {
        final HttpJsonTranscodingOptions withCamelCase =
                HttpJsonTranscodingOptions.builder()
                                          .queryParamMatchRules(LOWER_CAMEL_CASE)
                                          .build();
        assertThat(withCamelCase.queryParamMatchRules())
                .containsExactly(LOWER_CAMEL_CASE);

        final HttpJsonTranscodingOptions onlyCamelCase =
                HttpJsonTranscodingOptions.builder()
                                          .queryParamMatchRules(ORIGINAL_FIELD)
                                          .build();
        assertThat(onlyCamelCase.queryParamMatchRules()).containsExactly(ORIGINAL_FIELD);

        final HttpJsonTranscodingOptions onlyOriginalField =
                HttpJsonTranscodingOptions.builder()
                                          .queryParamMatchRules(ImmutableList.of(LOWER_CAMEL_CASE,
                                                                                 ORIGINAL_FIELD))
                                          .build();
        assertThat(onlyOriginalField.queryParamMatchRules())
                .containsExactlyInAnyOrder(ORIGINAL_FIELD, LOWER_CAMEL_CASE);

        final HttpJsonTranscodingOptions defaultOptions = HttpJsonTranscodingOptions.of();
        assertThat(defaultOptions.queryParamMatchRules()).containsExactly(ORIGINAL_FIELD, JSON_NAME);

        final HttpJsonTranscodingOptions onlyJsonName =
                HttpJsonTranscodingOptions.builder()
                                          .queryParamMatchRules(JSON_NAME)
                                          .build();
        assertThat(onlyJsonName.queryParamMatchRules()).containsExactly(JSON_NAME, ORIGINAL_FIELD);
    }

    @Test
    void shouldCopyAllFieldsFromExistingOptions() {
        final HttpRule rule = HttpRule.newBuilder()
                                      .setSelector("test.Service/Method")
                                      .setGet("/v1/method")
                                      .build();

        final HttpJsonTranscodingOptions original =
                HttpJsonTranscodingOptions.builder()
                                          .useHttpAnnotations(false)
                                          .additionalHttpRules(rule)
                                          .queryParamMatchRules(LOWER_CAMEL_CASE, ORIGINAL_FIELD)
                                          .errorHandler(UnframedGrpcErrorHandler.ofJson())
                                          .build();

        final HttpJsonTranscodingOptions copy = new HttpJsonTranscodingOptionsBuilder(original).build();

        assertThat(copy.useHttpAnnotations()).isEqualTo(original.useHttpAnnotations());
        assertThat(copy.additionalHttpRules()).isEqualTo(original.additionalHttpRules());
        assertThat(copy.queryParamMatchRules()).isEqualTo(original.queryParamMatchRules());
        assertThat(copy.errorHandler()).isEqualTo(original.errorHandler());
        assertThat(copy).isEqualTo(original);
    }
}
