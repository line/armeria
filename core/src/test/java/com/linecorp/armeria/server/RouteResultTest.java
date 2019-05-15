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
import java.util.AbstractMap.SimpleEntry;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.MediaType;

public class RouteResultTest {

    @Test
    public void empty() {
        final RouteResultBuilder builder = RouteResult.builder();
        final RouteResult routeResult = builder.build();
        assertThat(routeResult).isSameAs(RouteResult.empty());
    }

    @Test
    public void routeResult() throws URISyntaxException {
        final RouteResultBuilder builder = RouteResult.builder();
        final RouteResult routeResult = builder.path("/foo")
                                               .query("bar=baz")
                                               .pathParams(ImmutableMap.of("qux", "quux"))
                                               .negotiatedResponseMediaType(MediaType.JSON_UTF_8)
                                               .build();
        assertThat(routeResult.isPresent()).isTrue();
        assertThat(routeResult.path()).isEqualTo("/foo");
        assertThat(routeResult.query()).isEqualTo("bar=baz");
        assertThat(routeResult.pathParams()).containsOnly(new SimpleEntry<>("qux", "quux"));
        assertThat(routeResult.negotiatedResponseMediaType()).isSameAs(MediaType.JSON_UTF_8);
    }
}
