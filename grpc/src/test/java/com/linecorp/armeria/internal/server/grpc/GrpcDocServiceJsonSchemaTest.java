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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Descriptors.ServiceDescriptor;

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

    private static final ServiceDescriptor TEST_SERVICE_DESCRIPTOR =
            testing.grpc.Test.getDescriptor()
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
        final AggregatedHttpResponse res = client.get("/docs/schemas.json").aggregate().join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final ObjectMapper mapper = new ObjectMapper();

        final JsonNode schemaJson = mapper.readTree(res.contentUtf8());

        return ImmutableList.copyOf(schemaJson::elements);
    }

    @Test
    void testOk() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }

        final List<JsonNode> jsonSchemas = getJsonSchemas();

        assertThat(jsonSchemas).hasSize(1);
    }

    @Test
    void testBaseTypes() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);

        assertThatJson(jsonSchema).node("properties.bool.type").isEqualTo("boolean");
        assertThatJson(jsonSchema).node("properties.int32.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.int64.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.uint32.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.uint64.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.sint32.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.sint64.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.fixed32.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.fixed64.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.float.type").isEqualTo("number");
        assertThatJson(jsonSchema).node("properties.double.type").isEqualTo("number");
        assertThatJson(jsonSchema).node("properties.string.type").isEqualTo("string");
        assertThatJson(jsonSchema).node("properties.bytes.type").isEqualTo("string");
    }

    @Test
    void testEnum() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);

        assertThatJson(jsonSchema).node("properties.test_enum.type").isEqualTo("string");
        assertThatJson(jsonSchema).node("properties.test_enum.enum").isEqualTo(
                ImmutableList.of("ZERO", "ONE", "TWO"));
    }

    @Test
    void testRepeated() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);

        final JsonNode stringsField = jsonSchema.get("properties").get("complex_other_message").get(
                "properties").get("strings");
        assertThatJson(stringsField).node("type").isEqualTo("array");
        assertThatJson(stringsField).node("items.type").isEqualTo("string");

        final JsonNode nestedsField = jsonSchema.get("properties").get("nesteds");
        assertThatJson(nestedsField).node("type").isEqualTo("array");
        assertThatJson(nestedsField).node("items.$ref").isEqualTo("#/properties/nested");

        final JsonNode selvesField = jsonSchema.get("properties").get("selves");
        assertThatJson(selvesField).node("type").isEqualTo("array");
        assertThatJson(selvesField).node("items.$ref").isEqualTo("#");
    }

    @Test
    void testMap() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);
        final JsonNode properties = jsonSchema.get("properties");

        assertThatJson(properties).node("int_to_string_map.type").isEqualTo("object");
        assertThatJson(properties).node("int_to_string_map.additionalProperties.type").isEqualTo("string");

        // "string_to_int_map" references to "#/properties/complex_other_message/properties/map"
        assertThatJson(properties).node("string_to_int_map.$ref").isEqualTo(
                "#/properties/complex_other_message/properties/map");
        final JsonNode stringToIntMap = properties.get("complex_other_message").get("properties")
                                                  .get("map");
        assertThatJson(stringToIntMap).node("type").isEqualTo("object");
        assertThatJson(stringToIntMap).node("additionalProperties.type").isEqualTo("integer");

        assertThatJson(properties).node("message_map.type").isEqualTo("object");
        assertThatJson(properties).node("message_map.additionalProperties.$ref").isEqualTo(
                "#/properties/nested");

        assertThatJson(properties).node("self_map.additionalProperties.$ref").isEqualTo("#");
        assertThatJson(properties).node("self_map.type").isEqualTo("object");
    }

    @Test
    void testMessage() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);

        assertThatJson(jsonSchema).node("properties.nested.type").isEqualTo("object");
        assertThatJson(jsonSchema).node("properties.nested.properties.string.type").isEqualTo("string");
    }

    @Test
    void testRecursiveMessage() throws Exception {
        final JsonNode jsonSchema = getJsonSchemas().get(0);

        assertThatJson(jsonSchema).node("properties.self.$ref").isEqualTo("#");
        assertThatJson(jsonSchema).node("properties.nested_self.properties.self.$ref").isEqualTo("#");
        assertThatJson(jsonSchema).node("properties.nested_nested_self.properties.nested_self.$ref").isEqualTo(
                "#/properties/nested_self");
    }
}
