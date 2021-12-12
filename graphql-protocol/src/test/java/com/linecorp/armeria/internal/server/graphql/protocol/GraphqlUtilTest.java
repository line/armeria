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

package com.linecorp.armeria.internal.server.graphql.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;

class GraphqlUtilTest {

    @ParameterizedTest
    @MethodSource("provideMediaType")
    void test(RequestHeaders requestHeaders, MediaType actual) {
        final MediaType expected = GraphqlUtil.produceType(requestHeaders);
        assertThat(actual).isEqualTo(expected);
    }

    private static Stream<Arguments> provideMediaType() {
        return Stream.of(
                Arguments.of(RequestHeaders.builder(HttpMethod.GET, "/graphql")
                                           .contentType(MediaType.ANY_TYPE)
                                           .accept(MediaType.ANY_TYPE)
                                           .build(),
                             MediaType.GRAPHQL_JSON),
                Arguments.of(RequestHeaders.builder(HttpMethod.GET, "/graphql")
                                           .contentType(MediaType.ANY_TYPE)
                                           .accept(MediaType.ANY_APPLICATION_TYPE)
                                           .build(),
                             MediaType.GRAPHQL_JSON),
                Arguments.of(RequestHeaders.builder(HttpMethod.GET, "/graphql")
                                           .contentType(MediaType.ANY_TYPE)
                                           .accept(MediaType.JSON)
                                           .build(),
                             MediaType.JSON),
                Arguments.of(RequestHeaders.builder(HttpMethod.GET, "/graphql")
                                           .contentType(MediaType.ANY_TYPE)
                                           .accept(MediaType.GRAPHQL_JSON)
                                           .build(),
                             MediaType.GRAPHQL_JSON),
                Arguments.of(RequestHeaders.builder(HttpMethod.GET, "/graphql")
                                           .contentType(MediaType.ANY_TYPE)
                                           // accept is null
                                           .build(),
                             MediaType.GRAPHQL_JSON),
                Arguments.of(RequestHeaders.builder(HttpMethod.GET, "/graphql")
                                           .contentType(MediaType.ANY_TYPE)
                                           .accept(MediaType.GRAPHQL)
                                           .build(),
                             null),
                Arguments.of(RequestHeaders.builder(HttpMethod.GET, "/graphql")
                                           .contentType(MediaType.ANY_TYPE)
                                           .accept(MediaType.ANY_TEXT_TYPE)
                                           .build(),
                             null),
                Arguments.of(RequestHeaders.builder(HttpMethod.POST, "/graphql")
                                           .contentType(MediaType.GRAPHQL)
                                           .accept(MediaType.GRAPHQL_JSON)
                                           .build(),
                             MediaType.GRAPHQL_JSON),
                Arguments.of(RequestHeaders.builder(HttpMethod.POST, "/graphql")
                                           .contentType(MediaType.JSON)
                                           .accept(MediaType.JSON)
                                           .build(),
                             MediaType.JSON),
                Arguments.of(RequestHeaders.builder(HttpMethod.POST, "/graphql")
                                           .contentType(MediaType.JSON)
                                           .accept(MediaType.GRAPHQL_JSON)
                                           .build(),
                             MediaType.GRAPHQL_JSON)
        );
    }
}
