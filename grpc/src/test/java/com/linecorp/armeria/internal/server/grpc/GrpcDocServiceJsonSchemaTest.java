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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.ExtendedTestMessage;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcDocServiceJsonSchemaTest {

    // Define model names as constants for readability and to prevent typos.
    private static final String EXT_MESSAGE_NAME = "armeria.grpc.testing.ExtendedTestMessage";
    private static final String NESTED_MESSAGE_NAME = EXT_MESSAGE_NAME + ".Nested";
    private static final String NESTED_SELF_MESSAGE_NAME = EXT_MESSAGE_NAME + ".NestedSelf";
    private static final String NESTED_NESTED_SELF_MESSAGE_NAME = EXT_MESSAGE_NAME + ".NestedNestedSelf";
    private static final String TEST_MESSAGE_NAME = "armeria.grpc.testing.TestMessage";
    private static final String TEST_ENUM_NAME = "armeria.grpc.testing.TestEnum";

    private static class TestService extends TestServiceImplBase {
        @Override
        public void unaryCallWithAllDifferentParameterTypes(
                ExtendedTestMessage request,
                StreamObserver<ExtendedTestMessage> responseObserver) {
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

    private static JsonNode getJsonSchema() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/schemas.json").aggregate().join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final ObjectMapper mapper = new ObjectMapper();
        return mapper.readTree(res.contentUtf8());
    }

    // Helper method to create a path for JsonUnit assertions.
    // Dots in model names must be escaped for JsonUnit.
    private static String modelNodePath(String modelName) {
        return "$defs.models." + modelName.replace(".", "\\.");
    }

    // Helper method to create the expected string value for a $ref.
    private static String modelRefValue(String modelName) {
        return "#/$defs/models/" + modelName;
    }

    @Test
    void testOk() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }
        final JsonNode jsonSchema = getJsonSchema();
        assertThat(jsonSchema).isNotNull();
        assertThat(jsonSchema.path("$defs").path("methods")
                             .has("UnaryCallWithAllDifferentParameterTypes")).isTrue();
    }

    @Test
    void testBaseTypes() throws Exception {
        final JsonNode jsonSchema = getJsonSchema();
        final String propertiesPath = modelNodePath(EXT_MESSAGE_NAME) + ".properties";

        assertThatJson(jsonSchema).node(propertiesPath + ".bool.type").isEqualTo("boolean");
        assertThatJson(jsonSchema).node(propertiesPath + ".int32.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node(propertiesPath + ".int64.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node(propertiesPath + ".uint32.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node(propertiesPath + ".uint64.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node(propertiesPath + ".sint32.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node(propertiesPath + ".sint64.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node(propertiesPath + ".fixed32.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node(propertiesPath + ".fixed64.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node(propertiesPath + ".float.type").isEqualTo("number");
        assertThatJson(jsonSchema).node(propertiesPath + ".double.type").isEqualTo("number");
        assertThatJson(jsonSchema).node(propertiesPath + ".string.type").isEqualTo("string");
        assertThatJson(jsonSchema).node(propertiesPath + ".bytes.type").isEqualTo("string");
    }

    @Test
    void testEnum() throws Exception {
        final JsonNode jsonSchema = getJsonSchema();

        assertThatJson(jsonSchema).node(modelNodePath(EXT_MESSAGE_NAME) + ".properties.test_enum.$ref")
                                  .isEqualTo(modelRefValue(TEST_ENUM_NAME));
        assertThatJson(jsonSchema).node(modelNodePath(TEST_ENUM_NAME) + ".type").isEqualTo("string");
        assertThatJson(jsonSchema).node(modelNodePath(TEST_ENUM_NAME) + ".enum")
                                  .isEqualTo(ImmutableList.of("ZERO", "ONE", "TWO"));
    }

    @Test
    void testRepeated() throws Exception {
        final JsonNode jsonSchema = getJsonSchema();

        assertThatJson(jsonSchema).node(modelNodePath(TEST_MESSAGE_NAME) + ".properties.strings.type")
                                  .isEqualTo("array");
        assertThatJson(jsonSchema).node(modelNodePath(TEST_MESSAGE_NAME) + ".properties.strings.items.type")
                                  .isEqualTo("string");

        assertThatJson(jsonSchema).node(modelNodePath(EXT_MESSAGE_NAME) + ".properties.nesteds.type")
                                  .isEqualTo("array");
        assertThatJson(jsonSchema).node(modelNodePath(EXT_MESSAGE_NAME) + ".properties.nesteds.items.$ref")
                                  .isEqualTo(modelRefValue(NESTED_MESSAGE_NAME));

        assertThatJson(jsonSchema).node(modelNodePath(EXT_MESSAGE_NAME) + ".properties.selves.type")
                                  .isEqualTo("array");
        assertThatJson(jsonSchema).node(modelNodePath(EXT_MESSAGE_NAME) + ".properties.selves.items.$ref")
                                  .isEqualTo(modelRefValue(EXT_MESSAGE_NAME));
    }

    @Test
    void testMap() throws Exception {
        final JsonNode jsonSchema = getJsonSchema();
        final String propertiesPath = modelNodePath(EXT_MESSAGE_NAME) + ".properties";

        assertThatJson(jsonSchema).node(propertiesPath + ".int_to_string_map.type").isEqualTo("object");
        assertThatJson(jsonSchema).node(propertiesPath + ".int_to_string_map.additionalProperties.type")
                                  .isEqualTo("string");

        assertThatJson(jsonSchema).node(propertiesPath + ".string_to_int_map.type").isEqualTo("object");
        assertThatJson(jsonSchema).node(propertiesPath + ".string_to_int_map.additionalProperties.type")
                                  .isEqualTo("integer");

        assertThatJson(jsonSchema).node(propertiesPath + ".message_map.type").isEqualTo("object");
        assertThatJson(jsonSchema).node(propertiesPath + ".message_map.additionalProperties.$ref")
                                  .isEqualTo(modelRefValue(NESTED_MESSAGE_NAME));

        assertThatJson(jsonSchema).node(propertiesPath + ".self_map.type").isEqualTo("object");
        assertThatJson(jsonSchema).node(propertiesPath + ".self_map.additionalProperties.$ref")
                                  .isEqualTo(modelRefValue(EXT_MESSAGE_NAME));
    }

    @Test
    void testMessage() throws Exception {
        final JsonNode jsonSchema = getJsonSchema();

        assertThatJson(jsonSchema).node(modelNodePath(EXT_MESSAGE_NAME) + ".properties.nested.$ref")
                                  .isEqualTo(modelRefValue(NESTED_MESSAGE_NAME));
        assertThatJson(jsonSchema).node(modelNodePath(NESTED_MESSAGE_NAME) + ".type").isEqualTo("object");
        assertThatJson(jsonSchema).node(modelNodePath(NESTED_MESSAGE_NAME) + ".properties.string.type")
                                  .isEqualTo("string");
    }

    @Test
    void testRecursiveMessage() throws Exception {
        final JsonNode jsonSchema = getJsonSchema();

        assertThatJson(jsonSchema).node(modelNodePath(EXT_MESSAGE_NAME) + ".properties.self.$ref")
                                  .isEqualTo(modelRefValue(EXT_MESSAGE_NAME));
        assertThatJson(jsonSchema).node(modelNodePath(NESTED_SELF_MESSAGE_NAME) + ".properties.self.$ref")
                                  .isEqualTo(modelRefValue(EXT_MESSAGE_NAME));
        assertThatJson(jsonSchema).node(
                                          modelNodePath(NESTED_NESTED_SELF_MESSAGE_NAME) +
                                          ".properties.nested_self.$ref")
                                  .isEqualTo(modelRefValue(NESTED_SELF_MESSAGE_NAME));
    }
}
