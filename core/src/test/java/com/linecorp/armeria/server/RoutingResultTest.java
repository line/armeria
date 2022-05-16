/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Maps;

import com.linecorp.armeria.common.MediaType;

class RoutingResultTest {

    @Test
    void empty() {
        final RoutingResultBuilder builder = RoutingResult.builder();
        final RoutingResult routingResult = builder.build();
        assertThat(routingResult).isSameAs(RoutingResult.empty());
    }

    @Test
    void routingResult() throws URISyntaxException {
        final RoutingResultBuilder builder = RoutingResult.builder();
        final RoutingResult routingResult = builder.path("/foo")
                                                   .query("bar=baz")
                                                   .rawParam("qux", "quux")
                                                   .negotiatedResponseMediaType(MediaType.JSON_UTF_8)
                                                   .build();
        assertThat(routingResult.isPresent()).isTrue();
        assertThat(routingResult.path()).isEqualTo("/foo");
        assertThat(routingResult.query()).isEqualTo("bar=baz");
        assertThat(routingResult.pathParams()).containsOnly(Maps.immutableEntry("qux", "quux"));
        assertThat(routingResult.negotiatedResponseMediaType()).isSameAs(MediaType.JSON_UTF_8);
    }

    @Test
    void percentEncodedPathParam() {
        final RoutingResultBuilder builder = RoutingResult.builder();
        final RoutingResult routingResult = builder.path("/foo")
                                                   .rawParam("bar", "%62az%2Fqu%78")
                                                   .build();
        assertThat(routingResult.pathParams()).containsOnly(Maps.immutableEntry("bar", "baz/qux"));
    }
}
