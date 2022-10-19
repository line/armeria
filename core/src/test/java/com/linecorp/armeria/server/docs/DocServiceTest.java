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

package com.linecorp.armeria.server.docs;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DocServiceTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService()
              .build(new Object() {
                  @Get("/")
                  public String greeting(String hello) {
                      return "world";
                  }
              });
            sb.serviceUnder("/docs",
                            DocService.builder().lazyLoad(false).build());
            sb.serviceUnder("/lazyDocs",
                            DocService.builder().lazyLoad(true).build());
        }
    };

    @ParameterizedTest
    @ValueSource(strings =  {"/docs/specification.json", "/lazyDocs/specification.json"})
    void testLazySpecifications(String path) {
        final String specification = server.blockingWebClient().get(path).contentUtf8();
        assertThatJson(specification).node("services").isArray().ofLength(1);
        assertThatJson(specification).node("services[0].methods").isArray().ofLength(1);
        assertThatJson(specification).node("services[0].methods[0].name").isStringEqualTo("greeting");
    }

    @ParameterizedTest
    @ValueSource(strings =  {"/docs/versions.json", "/lazyDocs/versions.json"})
    void testLazyVersions(String path) {
        final String versions = server.blockingWebClient().get(path).contentUtf8();
        assertThatJson(versions).node("[0].artifactId").isString();
    }
}
