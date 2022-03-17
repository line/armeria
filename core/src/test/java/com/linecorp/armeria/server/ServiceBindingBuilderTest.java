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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.assertj.core.util.Files;
import org.junit.Test;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.logging.AccessLogWriter;

public class ServiceBindingBuilderTest {

    @Test
    public void serviceBindingBuilder() {
        final ServerBuilder sb = Server.builder();
        final AccessLogWriter accessLogWriter = mock(AccessLogWriter.class);
        final Path multipartUploadsLocation = Files.newTemporaryFolder().toPath();

        sb.route().get("/foo/bar")
          .consumes(JSON, PLAIN_TEXT_UTF_8)
          .produces(JSON_UTF_8, PLAIN_TEXT_UTF_8)
          .requestTimeoutMillis(10)
          .maxRequestLength(8192)
          .verboseResponses(true)
          .accessLogWriter(accessLogWriter, true)
          .multipartUploadsLocation(multipartUploadsLocation)
          .build((ctx, req) -> HttpResponse.of(OK));

        final List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        final ServiceConfig serviceConfig = serviceConfigs.get(0);

        final Route route = serviceConfig.route();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar", "/foo/bar");
        assertThat(route.consumes()).containsExactly(JSON, PLAIN_TEXT_UTF_8);
        assertThat(route.produces()).containsExactly(JSON_UTF_8,
                                                     PLAIN_TEXT_UTF_8);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(10);
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(8192);
        assertThat(serviceConfig.verboseResponses()).isEqualTo(true);
        assertThat(serviceConfig.accessLogWriter()).isSameAs(accessLogWriter);
        assertThat(serviceConfig.shutdownAccessLogWriterOnStop()).isTrue();
        assertThat(serviceConfig.multipartUploadsLocation()).isSameAs(multipartUploadsLocation);
    }

    @Test
    public void withRoute() {
        final Path multipartUploadsLocation = Files.newTemporaryFolder().toPath();

        final ServerBuilder sb = Server.builder();
        sb.withRoute(builder -> builder.get("/foo/bar")
                                       .consumes(JSON, PLAIN_TEXT_UTF_8)
                                       .produces(JSON_UTF_8, PLAIN_TEXT_UTF_8)
                                       .requestTimeoutMillis(10)
                                       .maxRequestLength(8192)
                                       .verboseResponses(true)
                                       .multipartUploadsLocation(multipartUploadsLocation)
                                       .build((ctx, req) -> HttpResponse.of(OK)));
        final List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        final ServiceConfig serviceConfig = serviceConfigs.get(0);

        final Route route = serviceConfig.route();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar", "/foo/bar");
        assertThat(route.consumes()).containsExactly(JSON, PLAIN_TEXT_UTF_8);
        assertThat(route.produces()).containsExactly(JSON_UTF_8,
                                                     PLAIN_TEXT_UTF_8);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(10);
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(8192);
        assertThat(serviceConfig.verboseResponses()).isEqualTo(true);
        assertThat(serviceConfig.multipartUploadsLocation()).isSameAs(multipartUploadsLocation);
    }

    @Test
    public void overwriteServerBuilderProperty() {
        final AccessLogWriter accessLogWriter = mock(AccessLogWriter.class);
        final Path overWrittenMultipartUploadsLocation = Files.newTemporaryFolder().toPath();
        final Path routeMultipartUploadsLocation = Files.newTemporaryFolder().toPath();

        final ServerBuilder sb = Server.builder();
        sb.defaultVirtualHost()
          .maxRequestLength(1024)
          .accessLogWriter(accessLogWriter, false)
          .requestTimeoutMillis(10000) // This is overwritten.
          .multipartUploadsLocation(overWrittenMultipartUploadsLocation) // This is overwritten
          .defaultServiceNaming(ctx -> "globalServiceNaming"); // This is overwritten.

        sb.route().get("/foo/bar")
          .requestTimeout(Duration.ofMillis(10))
          .defaultServiceNaming(ctx -> "serviceNaming")
          .multipartUploadsLocation(routeMultipartUploadsLocation)
          .build((ctx, req) -> HttpResponse.of(OK));

        sb.defaultVirtualHost().maxRequestLength(1024);
        sb.verboseResponses(true);

        final List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        final ServiceConfig serviceConfig = serviceConfigs.get(0);
        final Route route = serviceConfig.route();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar", "/foo/bar");
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(10);
        assertThat(serviceConfig.defaultServiceNaming()).isNotNull();
        assertThat(serviceConfig.multipartUploadsLocation()).isSameAs(routeMultipartUploadsLocation);
        final ServiceRequestContext sctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                                .build();
        assertThat(serviceConfig.defaultServiceNaming().serviceName(sctx)).isEqualTo("serviceNaming");
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(1024);
        assertThat(serviceConfig.verboseResponses()).isEqualTo(true);
        assertThat(serviceConfig.accessLogWriter()).isSameAs(accessLogWriter);
        assertThat(serviceConfig.shutdownAccessLogWriterOnStop()).isFalse();
    }

    @Test
    public void onePathWithTwoMethods() {
        ServerBuilder sb = Server.builder();
        sb.route()
          .get("/foo/bar")
          .post("/foo/bar")
          .build((ctx, req) -> HttpResponse.of(OK));

        List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        Route route = serviceConfigs.get(0).route();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar", "/foo/bar");

        sb = Server.builder();
        sb.route().path("/foo/bar")
          .methods(HttpMethod.GET, HttpMethod.POST)
          .build((ctx, req) -> HttpResponse.of(OK));

        serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isOne();
        route = serviceConfigs.get(0).route();
        assertThat(route.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route.paths()).containsExactly("/foo/bar", "/foo/bar");
    }

    @Test
    public void twoPaths() {
        final ServerBuilder sb = Server.builder();
        sb.route()
          .get("/foo/bar")
          .get("/foo/bar/baz")
          .build((ctx, req) -> HttpResponse.of(OK));

        final List<ServiceConfig> serviceConfigs = sb.build().serviceConfigs();
        assertThat(serviceConfigs.size()).isEqualTo(2);
        final Route route1 = serviceConfigs.get(0).route();
        assertThat(route1.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route1.paths()).containsExactly("/foo/bar", "/foo/bar");

        final Route route2 = serviceConfigs.get(1).route();
        assertThat(route2.pathType()).isSameAs(RoutePathType.EXACT);
        assertThat(route2.paths()).containsExactly("/foo/bar/baz", "/foo/bar/baz");
    }

    @Test(expected = IllegalStateException.class)
    public void shouldSpecifyAtLeastOnePath() {
        final ServiceBindingBuilder serviceBindingBuilder = new ServiceBindingBuilder(Server.builder());
        serviceBindingBuilder.build((ctx, req) -> HttpResponse.of(OK));
    }

    @Test(expected = IllegalArgumentException.class)
    public void methodsCannotBeEmpty() {
        final ServiceBindingBuilder serviceBindingBuilder = new ServiceBindingBuilder(Server.builder());
        serviceBindingBuilder.methods(ImmutableSet.of());
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotSetSameMethodToTheSamePath() {
        final ServiceBindingBuilder serviceBindingBuilder = new ServiceBindingBuilder(Server.builder());
        serviceBindingBuilder.get("/foo/bar");
        serviceBindingBuilder.path("/foo/bar");
        serviceBindingBuilder.methods(HttpMethod.GET);
        serviceBindingBuilder.build((ctx, req) -> HttpResponse.of(OK));
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotSetSameMethodToTheSamePath2() {
        final ServiceBindingBuilder serviceBindingBuilder = new ServiceBindingBuilder(Server.builder());
        serviceBindingBuilder.get("/foo/bar");
        serviceBindingBuilder.get("/foo/bar");
    }
}
