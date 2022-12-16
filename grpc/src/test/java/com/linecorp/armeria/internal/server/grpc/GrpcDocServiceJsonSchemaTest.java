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

package com.linecorp.armeria.internal.server.grpc;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.grpc.testing.Messages.ExtendedTestMessage;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;

class GrpcDocServiceJsonSchemaTest {

    private static final ServiceDescriptor TEST_SERVICE_DESCRIPTOR =
            com.linecorp.armeria.grpc.testing.Test.getDescriptor()
                                                  .findServiceByName("TestService");

    private static class TestService extends TestServiceImplBase {
        @Override
        public void unaryCallWithAllDifferentParameterTypes(
                ExtendedTestMessage request,
                StreamObserver<ExtendedTestMessage> responseObserver
        ) {
            // Just return the requested object.
            responseObserver.onNext(request);
            responseObserver.onCompleted();
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8080);
            }
            sb.serviceUnder("/test",
                            GrpcService.builder()
                                       .addService(new TestService())
                                       .build());
            sb.serviceUnder("/docs/",
                            DocService.builder()
                                      .include(DocServiceFilter.ofMethodName(
                                              TestServiceGrpc.SERVICE_NAME,
                                              "UnaryCallWithAllDifferentParameterTypes"))
                                      .build()
                                      .decorate(LoggingService.newDecorator()));
        }
    };

    private static List<JsonNode> getJsonSchemas() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/schema.json").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final ObjectMapper mapper = new ObjectMapper();

        final JsonNode schemaJson = mapper.readTree(res.contentUtf8());

        return ImmutableList.copyOf(schemaJson::elements);
    }

    @Test
    @Timeout(10)
    void testOk() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }

        final List<JsonNode> jsonSchemas = getJsonSchemas();

        assert (jsonSchemas.size() == 1);
    }

    @Test
    @Timeout(10)
    void testBaseTypes() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);
        final JsonNode properties = jsonSchema.get("properties");

        assertThat(properties.get("bool").get("type").asText()).isEqualTo("boolean");
        assertThat(properties.get("int32").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("int64").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("uint32").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("uint64").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("sint32").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("sint64").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("fixed32").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("fixed64").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("float").get("type").asText()).isEqualTo("number");
        assertThat(properties.get("double").get("type").asText()).isEqualTo("number");
        assertThat(properties.get("string").get("type").asText()).isEqualTo("string");
        assertThat(properties.get("bytes").get("type").asText()).isEqualTo("string");
    }

    @Test
    @Timeout(10)
    void testEnum() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);
        final JsonNode properties = jsonSchema.get("properties");

        final JsonNode enumField = properties.get("test_enum");
        assertThat(enumField.get("type").asText()).isEqualTo("string");
        assertThat(enumField.has("enum")).isTrue();

        final List<String> enumValues = ImmutableList.copyOf(enumField.get("enum")::elements).stream()
                                                     .map(JsonNode::asText)
                                                     .collect(toImmutableList());

        assertThat(enumValues.size()).isEqualTo(3);
        assertThat(enumValues).containsExactlyInAnyOrder("ZERO", "ONE", "TWO");
    }

    @Test
    @Timeout(10)
    void testRepeated() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);
        final JsonNode properties = jsonSchema.get("properties");

        assertThat(properties.get("strings").get("type").asText()).isEqualTo("array");
        assertThat(properties.get("strings").get("items").get("type").asText()).isEqualTo("string");

        assertThat(properties.get("nesteds").get("type").asText()).isEqualTo("array");
        // TODO: Fine grained repeated fields
        assertThat(properties.get("nesteds").get("items").get("type").asText()).isEqualTo("object");

        assertThat(properties.get("selves").get("type").asText()).isEqualTo("array");
        // TODO: Fine grained repeated fields
        assertThat(properties.get("selves").get("items").get("type").asText()).isEqualTo("object");
    }

    @Test
    @Timeout(10)
    void testMap() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);
        final JsonNode properties = jsonSchema.get("properties");

        // TODO: Use additional properties to define map key - value schema
        ImmutableList.of("int_to_string_map", "string_to_int_map", "message_map", "self_map").forEach(
                mapName -> {
                    assertThat(properties.get(mapName).get("type").asText()).isEqualTo("object");
                    assertThat(properties.get(mapName).get("additionalProperties").asBoolean()).isTrue();
                }
        );
    }

    @Test
    @Timeout(10)
    void testMessage() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);
        final JsonNode properties = jsonSchema.get("properties");

        assertThat(properties.get("nested").get("type").asText()).isEqualTo("object");
        assertThat(properties.get("nested").get("properties").has("string")).isTrue();
        assertThat(properties.get("nested").get("properties").get("string").get("type").asText()).isEqualTo(
                "string");
    }

    @Test
    @Timeout(10)
    void testRecursiveMessage() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);
        final JsonNode properties = jsonSchema.get("properties");

        assertThat(properties.get("self").has("type")).isFalse();
        assertThat(properties.get("self").get("$ref").asText()).isEqualTo("#");

        assertThat(properties.get("nested_self").get("properties").has("self")).isTrue();
        assertThat(properties.get("nested_self").get("properties").get("self").get("$ref").asText()).isEqualTo(
                "#");

        assertThat(properties.get("nested_nested_self").get("properties").has("nested_self")).isTrue();
        assertThat(properties.get("nested_nested_self").get("properties").get("nested_self").get("$ref")
                             .asText()).isEqualTo("#/nested_self");
    }
}
