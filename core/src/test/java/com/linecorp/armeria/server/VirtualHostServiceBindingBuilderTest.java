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

import static com.linecorp.armeria.common.HttpStatus.OK;
import static com.linecorp.armeria.common.MediaType.JSON;
import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;

class VirtualHostServiceBindingBuilderTest {

    @Test
    void serviceBindingBuilder() {
        final ServerBuilder sb = new ServerBuilder();
        final ContentPreviewerFactory requestFactory = mock(ContentPreviewerFactory.class);
        final ContentPreviewerFactory responseFactory = mock(ContentPreviewerFactory.class);

        sb.virtualHost("example.com")
          .route().pathUnder("/foo/bar")
          .methods(HttpMethod.GET)
          .consumes(JSON, PLAIN_TEXT_UTF_8)
          .produces(JSON_UTF_8, PLAIN_TEXT_UTF_8)
          .requestTimeoutMillis(10)
          .maxRequestLength(8192)
          .verboseResponses(true)
          .requestContentPreviewerFactory(requestFactory)
          .responseContentPreviewerFactory(responseFactory)
          .build((ctx, req) -> HttpResponse.of(OK));

        final List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        final ServiceConfig serviceConfig = serviceConfigs.get(0);

        final Route route = serviceConfig.route();
        assertThat(route.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(route.paths()).containsExactly("/foo/bar/", "/foo/bar/*");
        assertThat(route.consumes()).containsExactly(JSON, PLAIN_TEXT_UTF_8);
        assertThat(route.produces()).containsExactly(JSON_UTF_8,
                                                               PLAIN_TEXT_UTF_8);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(10);
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(8192);
        assertThat(serviceConfig.verboseResponses()).isEqualTo(true);
        assertThat(serviceConfig.requestContentPreviewerFactory()).isSameAs(requestFactory);
        assertThat(serviceConfig.responseContentPreviewerFactory()).isSameAs(responseFactory);
    }

    @Test
    void withRoute() {
        final ServerBuilder sb = new ServerBuilder();

        sb.virtualHost("example.com").withRoute(builder -> {
            builder.pathUnder("/foo/bar")
                   .methods(HttpMethod.GET)
                   .consumes(JSON, PLAIN_TEXT_UTF_8)
                   .produces(JSON_UTF_8, PLAIN_TEXT_UTF_8)
                   .requestTimeoutMillis(10)
                   .maxRequestLength(8192)
                   .verboseResponses(true)
                   .build((ctx, req) -> HttpResponse.of(OK));
        });

        final List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        final ServiceConfig serviceConfig = serviceConfigs.get(0);

        final Route route = serviceConfig.route();
        assertThat(route.pathType()).isSameAs(RoutePathType.PREFIX);
        assertThat(route.paths()).containsExactly("/foo/bar/", "/foo/bar/*");
        assertThat(route.consumes()).containsExactly(JSON, PLAIN_TEXT_UTF_8);
        assertThat(route.produces()).containsExactly(JSON_UTF_8,
                                                               PLAIN_TEXT_UTF_8);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(10);
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(8192);
        assertThat(serviceConfig.verboseResponses()).isEqualTo(true);
    }
}
