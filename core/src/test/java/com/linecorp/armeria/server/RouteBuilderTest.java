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

import java.time.Duration;
import java.util.List;

import org.junit.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;

public class RouteBuilderTest {

    @Test
    public void routeBuilder() {
        final ServerBuilder sb = new ServerBuilder();
        final ContentPreviewerFactory requestFactory = mock(ContentPreviewerFactory.class);
        final ContentPreviewerFactory responseFactory = mock(ContentPreviewerFactory.class);
        sb.requestContentPreviewerFactory(requestFactory);
        sb.responseContentPreviewerFactory(responseFactory);

        sb.route().get("/foo/bar")
          .consumes(JSON, PLAIN_TEXT_UTF_8)
          .produces(JSON_UTF_8, PLAIN_TEXT_UTF_8)
          .requestTimeoutMillis(10)
          .maxRequestLength(8192)
          .verboseResponses(true)
          .requestContentPreviewerFactory(ContentPreviewerFactory.disabled())
          .service((ctx, req) -> HttpResponse.of(OK));

        final List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        final ServiceConfig serviceConfig = serviceConfigs.get(0);

        final PathMapping pathMapping = serviceConfig.pathMapping();
        assertThat(pathMapping.exactPath().get()).isEqualTo("/foo/bar");
        assertThat(pathMapping.consumeTypes()).containsExactly(JSON, PLAIN_TEXT_UTF_8);
        assertThat(pathMapping.produceTypes()).containsExactly(JSON_UTF_8,
                                                               PLAIN_TEXT_UTF_8);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(10);
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(8192);
        assertThat(serviceConfig.verboseResponses()).isEqualTo(true);
        assertThat(serviceConfig.requestContentPreviewerFactory()).isSameAs(ContentPreviewerFactory.disabled());
        assertThat(serviceConfig.responseContentPreviewerFactory()).isSameAs(responseFactory);
    }

    @Test
    public void overwriteServerBuilderProperty() {
        final ServerBuilder sb = new ServerBuilder();
        sb.withDefaultVirtualHost()
          .maxRequestLength(1024)
          .requestTimeoutMillis(10000); // This is overwritten.

        sb.route().get("/foo/bar")
          .requestTimeout(Duration.ofMillis(10))
          .service((ctx, req) -> HttpResponse.of(OK));

        sb.withDefaultVirtualHost().maxRequestLength(1024);
        sb.verboseResponses(true);

        final List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        final ServiceConfig serviceConfig = serviceConfigs.get(0);
        final PathMapping pathMapping = serviceConfig.pathMapping();
        assertThat(pathMapping.exactPath().get()).isEqualTo("/foo/bar");
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(10);
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(1024);
        assertThat(serviceConfig.verboseResponses()).isEqualTo(true);
    }

    @Test
    public void onePathWithTwoMethods() {
        ServerBuilder sb = new ServerBuilder();
        sb.route()
          .get("/foo/bar")
          .post("/foo/bar")
          .service((ctx, req) -> HttpResponse.of(OK));

        List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        assertThat(serviceConfigs.get(0).pathMapping().exactPath().get()).isEqualTo("/foo/bar");

        sb = new ServerBuilder();
        sb.route().pathMapping(PathMapping.of("/foo/bar"))
          .methods(HttpMethod.GET, HttpMethod.POST)
          .service((ctx, req) -> HttpResponse.of(OK));

        serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        assertThat(serviceConfigs.get(0).pathMapping().exactPath().get()).isEqualTo("/foo/bar");
    }

    @Test
    public void twoPaths() {
        final ServerBuilder sb = new ServerBuilder();
        sb.route()
          .get("/foo/bar")
          .get("/foo/bar/baz")
          .service((ctx, req) -> HttpResponse.of(OK));

        final List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isEqualTo(2);
        assertThat(serviceConfigs.get(0).pathMapping().exactPath().get()).isEqualTo("/foo/bar");
        assertThat(serviceConfigs.get(1).pathMapping().exactPath().get()).isEqualTo("/foo/bar/baz");
    }

    @Test(expected = IllegalStateException.class)
    public void shouldSpecifyAtLeastOnePath() {
        final RouteBuilder routeBuilder = new RouteBuilder(new ServerBuilder());
        routeBuilder.service((ctx, req) -> HttpResponse.of(OK));
    }

    @Test(expected = IllegalArgumentException.class)
    public void methodsCannotBeEmpty() {
        final RouteBuilder routeBuilder = new RouteBuilder(new ServerBuilder());
        routeBuilder.methods(ImmutableSet.of());
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotSetSameMethodToTheSamePath() {
        final RouteBuilder routeBuilder = new RouteBuilder(new ServerBuilder());
        routeBuilder.get("/foo/bar");
        routeBuilder.path("/foo/bar");
        routeBuilder.methods(HttpMethod.GET);
        routeBuilder.service((ctx, req) -> HttpResponse.of(OK));
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotSetSameMethodToTheSamePath2() {
        final RouteBuilder routeBuilder = new RouteBuilder(new ServerBuilder());
        routeBuilder.get("/foo/bar");
        routeBuilder.get("/foo/bar");
    }
}
