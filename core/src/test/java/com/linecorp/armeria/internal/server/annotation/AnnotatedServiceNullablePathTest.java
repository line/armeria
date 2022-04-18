/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceNullablePathTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/default", new Object() {
                @Get
                public HttpResponse defaultValue() {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            sb.annotatedService("/empty", new Object() {
                @Get("")
                public HttpResponse emptyValue() {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            sb.annotatedService("/multiple", new Object() {
                @Get
                @Path("/")
                public HttpResponse multipleValue() {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });
        }
    };

    @ParameterizedTest
    @CsvSource({ "/default, 200", "/empty, 200", "/multiple, 307", "/multiple/, 200" })
    void params(String path, int statusCode) {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        assertThat(client.get(path).status().code()).isEqualTo(statusCode);
    }

    @Test
    void testNullablePathFailureService() {
        assertThatThrownBy(() -> {
            Server.builder()
                  .annotatedService("/multiple", new Object() {
                      @Get("")
                      @Path("/")
                      public HttpResponse multipleValue() {
                          return HttpResponse.of(HttpStatus.OK);
                      }
                  })
                  .build();
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot specify both an HTTP mapping and a Path mapping");
    }
}
