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

package com.linecorp.armeria.internal.server.graphql;

import static com.linecorp.armeria.internal.server.graphql.GraphqlDocServicePlugin.DEFAULT_METHOD_NAME;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.ServiceSpecification;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.graphql.GraphqlService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import graphql.schema.DataFetcher;

class GraphqlDocServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8080);
            }
            final File graphqlSchemaFile =
                    new File(getClass().getResource("/test.graphqls").toURI());
            final GraphqlService service =
                    GraphqlService.builder()
                                  .schemaFile(graphqlSchemaFile)
                                  .runtimeWiring(c -> {
                                      final DataFetcher bar = dataFetcher("bar");
                                      c.type("Query",
                                             typeWiring -> typeWiring.dataFetcher("foo", bar));
                                  })
                                  .build();
            sb.service("/graphql", service);
            sb.serviceUnder("/docs",
                            DocService.builder().build());
            sb.serviceUnder("/excludeAll",
                            DocService.builder()
                                      .exclude(DocServiceFilter.ofGraphql())
                                      .build());
            sb.serviceUnder("/excludeAll2",
                            DocService.builder()
                                      .exclude(DocServiceFilter.ofMethodName(DEFAULT_METHOD_NAME))
                                      .build());
        }
    };

    private static DataFetcher<String> dataFetcher(String value) {
        return environment -> value;
    }

    @Test
    void jsonSpecification() throws InterruptedException {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }

        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.headers().get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-cache, must-revalidate");
        assertThatJson(res.contentUtf8())
                .when(IGNORING_ARRAY_ORDER)
                .node("services[0].name").isEqualTo("com.linecorp.armeria.server.graphql.DefaultGraphqlService")
                .node("services[0].methods[0].name").isEqualTo(DEFAULT_METHOD_NAME)
                .node("services[0].methods[0].returnTypeSignature").isEqualTo("json")
                .node("services[0].methods[0].parameters[0]").matches(
                        new CustomTypeSafeMatcher<Map<String, Object>>("query") {
                            @Override
                            protected boolean matchesSafely(Map<String, Object> fieldInfo) {
                                assertThatFieldInfo(fieldInfo, "query", FieldRequirement.REQUIRED,
                                                    TypeSignature.ofBase("string"));
                                return true;
                            }
                        })
                .node("services[0].methods[0].parameters[1]").matches(
                        new CustomTypeSafeMatcher<Map<String, Object>>("operationName") {
                            @Override
                            protected boolean matchesSafely(Map<String, Object> fieldInfo) {
                                assertThatFieldInfo(fieldInfo, "operationName", FieldRequirement.OPTIONAL,
                                                    TypeSignature.ofBase("string"));
                                return true;
                            }
                        })
                .node("services[0].methods[0].parameters[2]").matches(
                        new CustomTypeSafeMatcher<Map<String, Object>>("variables") {
                            @Override
                            protected boolean matchesSafely(Map<String, Object> fieldInfo) {
                                assertThatFieldInfo(fieldInfo, "variables", FieldRequirement.OPTIONAL,
                                                    TypeSignature.ofBase("map"));
                                return true;
                            }
                        })
                .node("services[0].methods[0].parameters[3]").matches(
                        new CustomTypeSafeMatcher<Map<String, Object>>("extensions") {
                            @Override
                            protected boolean matchesSafely(Map<String, Object> fieldInfo) {
                                assertThatFieldInfo(fieldInfo, "extensions", FieldRequirement.OPTIONAL,
                                                    TypeSignature.ofBase("map"));
                                return true;
                            }
                        })
                .node("services[0].methods[0].endpoints[0].pathMapping").isEqualTo("exact:/graphql");
    }

    private static void assertThatFieldInfo(Map<String, Object> fieldInfo, String name,
                                            FieldRequirement fieldRequirement, TypeSignature typeSignature) {
        assertThat(fieldInfo.get("name")).isEqualTo(name);
        assertThat(fieldInfo.get("requirement")).isEqualTo(fieldRequirement.name());
        assertThat(fieldInfo.get("typeSignature")).isEqualTo(typeSignature.signature());
    }

    @ParameterizedTest
    @ValueSource(strings = { "/excludeAll", "/excludeAll2" })
    void excludeAllServices(String path) throws IOException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get(path + "/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final JsonNode actualJson = mapper.readTree(res.contentUtf8());
        final JsonNode expectedJson = mapper.valueToTree(new ServiceSpecification(ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of(),
                                                                                  ImmutableList.of()));
        assertThatJson(actualJson).isEqualTo(expectedJson);
    }
}
