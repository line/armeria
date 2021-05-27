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
 * under the License
 */

package com.linecorp.armeria.server.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceNaming;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ServiceName;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceLogNameTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService("/default", new MyAnnotatedService());

            sb.annotatedService()
              .defaultServiceName("DefaultName")
              .pathPrefix("/default-name")
              .build(new MyAnnotatedService());

            sb.annotatedService()
              .defaultServiceNaming(ServiceNaming.of("DefaultNaming"))
              .pathPrefix("/default-naming")
              .build(new MyAnnotatedService());

            sb.annotatedService()
              .defaultServiceNaming(ServiceNaming.simpleTypeName())
              .pathPrefix("/simple-naming")
              .build(new MyAnnotatedService());

            sb.annotatedService()
              .defaultServiceNaming(ServiceNaming.fullTypeName())
              .pathPrefix("/full-naming")
              .build(new MyAnnotatedService());

            sb.annotatedService()
              .defaultServiceNaming(ServiceNaming.shorten())
              .pathPrefix("/shorten-naming")
              .build(new MyAnnotatedService());

            sb.annotatedService()
              .pathPrefix("/annotation")
              .build(new ServiceNameAnnotatedService());

            sb.annotatedService()
              .pathPrefix("/anonymous-simple")
              .defaultServiceNaming(ServiceNaming.simpleTypeName())
              .build(new Object() {
                  @Get("/service-name")
                  public HttpResponse serviceName(ServiceRequestContext ctx) {
                      sctx = ctx;
                      return HttpResponse.of(HttpStatus.OK);
                  }
              });
        }
    };

    private static WebClient client;
    private static volatile ServiceRequestContext sctx;

    @BeforeAll
    static void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @Test
    void serviceName_withLegacyDefaultName() {
        client.get("/default-name/service-name").aggregate().join();
        assertThat(sctx.config().defaultServiceNaming().serviceName(sctx)).isEqualTo("DefaultName");
        assertThat(sctx.config().defaultServiceName()).isEqualTo("DefaultName");
        assertThat(sctx.log().whenComplete().join().serviceName()).isEqualTo("DefaultName");
    }

    @Test
    void serviceName_withDefault() {
        client.get("/default/service-name").aggregate().join();
        assertThat(sctx.config().defaultServiceNaming().serviceName(sctx))
                .isEqualTo(MyAnnotatedService.class.getName());
        assertThat(sctx.config().defaultServiceName()).isNull();
        assertThat(sctx.log().whenComplete().join().serviceName())
                .isEqualTo(MyAnnotatedService.class.getName());
    }

    @Test
    void serviceName_withDefaultNaming() {
        client.get("/default-naming/service-name").aggregate().join();
        assertThat(sctx.config().defaultServiceNaming().serviceName(sctx)).isEqualTo("DefaultNaming");
        // ServiceNaming is used.
        assertThat(sctx.config().defaultServiceName()).isNull();
        assertThat(sctx.log().whenComplete().join().serviceName()).isEqualTo("DefaultNaming");
    }

    @Test
    void serviceName_withSimpleNaming() {
        client.get("/simple-naming/service-name").aggregate().join();
        final String expectedServiceName = AnnotatedServiceLogNameTest.class.getSimpleName() + '$' +
                                           MyAnnotatedService.class.getSimpleName();

        assertThat(sctx.config().defaultServiceNaming().serviceName(sctx))
                .isEqualTo(expectedServiceName);
        // ServiceNaming is used.
        assertThat(sctx.config().defaultServiceName()).isNull();
        assertThat(sctx.log().whenComplete().join().serviceName())
                .isEqualTo(expectedServiceName);
    }

    @Test
    void serviceName_withFullNaming() {
        client.get("/full-naming/service-name").aggregate().join();
        assertThat(sctx.config().defaultServiceNaming().serviceName(sctx))
                .isEqualTo(MyAnnotatedService.class.getName());
        // ServiceNaming is used.
        assertThat(sctx.config().defaultServiceName()).isNull();
        assertThat(sctx.log().whenComplete().join().serviceName())
                .isEqualTo(MyAnnotatedService.class.getName());
    }

    @Test
    void serviceName_withShortenNaming() {
        client.get("/shorten-naming/service-name").aggregate().join();
        final String expectedServiceName = "c.l.a.s.l." + AnnotatedServiceLogNameTest.class.getSimpleName() +
                                           '$' + MyAnnotatedService.class.getSimpleName();
        assertThat(sctx.config().defaultServiceNaming().serviceName(sctx))
                .isEqualTo(expectedServiceName);
        // ServiceNaming is used.
        assertThat(sctx.config().defaultServiceName()).isNull();
        assertThat(sctx.log().whenComplete().join().serviceName())
                .isEqualTo(expectedServiceName);
    }

    @Test
    void serviceName_withAnnotation() {
        client.get("/annotation/service-name").aggregate().join();
        assertThat(sctx.config().defaultServiceNaming().serviceName(sctx))
                .isEqualTo("MyService");
        assertThat(sctx.config().defaultServiceName()).isNull();
        assertThat(sctx.log().whenComplete().join().serviceName())
                .isEqualTo("MyService");
    }

    @Test
    void serviceName_withAnonymous() {
        client.get("/anonymous-simple/service-name").aggregate().join();
        assertThat(sctx.config().defaultServiceNaming().serviceName(sctx))
                .isEqualTo("AnnotatedServiceLogNameTest$1$1");
        // ServiceNaming is used.
        assertThat(sctx.config().defaultServiceName()).isNull();
        assertThat(sctx.log().whenComplete().join().serviceName())
                .matches("^AnnotatedServiceLogNameTest(\\$[0-9]+)+$");
    }

    @Test
    void globalDefaultServiceNamingIsApplied() {
        final Server server = Server.builder()
                                    .defaultServiceNaming(ctx -> "foo")
                                    .annotatedService(new MyAnnotatedService()).build();
        server.start().join();
        final WebClient client =
                WebClient.of("http://127.0.0.1:" + server.activeLocalPort(SessionProtocol.HTTP));
        client.get("/service-name").aggregate().join();
        assertThat(sctx.log().whenComplete().join().serviceName()).isEqualTo("foo");
        server.stop().join();
    }

    private static class MyAnnotatedService {
        @Get("/service-name")
        public HttpResponse serviceName(ServiceRequestContext ctx) {
            sctx = ctx;
            return HttpResponse.of(HttpStatus.OK);
        }
    }

    @ServiceName("MyService")
    private static class ServiceNameAnnotatedService {
        @Get("/service-name")
        public HttpResponse serviceName(ServiceRequestContext ctx) {
            sctx = ctx;
            return HttpResponse.of(HttpStatus.OK);
        }
    }
}
