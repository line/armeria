/*
 *  Copyright 2025 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;

import org.apache.tools.ant.filters.StringInputStream;
import org.junit.jupiter.api.Test;

import com.google.api.client.testing.util.TestableByteArrayOutputStream;
import com.google.protobuf.util.JsonFormat;

import io.grpc.MethodDescriptor;
import testing.grpc.Messages;
import testing.grpc.TestServiceGrpc;

class GsonGrpcJsonMarshallerBuilderTest {
    private static final Messages.SimpleRequest testData = Messages.SimpleRequest.newBuilder()
            .setFillUsername(true)
            .setPayload(
                    Messages.Payload.newBuilder()
                            .setType(Messages.PayloadType.RANDOM)
                            .build()
            )
            .build();

    private static final MethodDescriptor.Marshaller<Messages.SimpleRequest> customRequestMarshaller =
            new MethodDescriptor.PrototypeMarshaller<Messages.SimpleRequest>() {
                @Override
                public Class<Messages.SimpleRequest> getMessageClass() {
                    return Messages.SimpleRequest.class;
                }

                @Override
                public Messages.SimpleRequest getMessagePrototype() {
                    return Messages.SimpleRequest.getDefaultInstance();
                }

                @Override
                public InputStream stream(Messages.SimpleRequest value) {
                    return TestServiceGrpc.getUnaryCallMethod().getRequestMarshaller().stream(value);
                }

                @Override
                public Messages.SimpleRequest parse(InputStream stream) {
                    return TestServiceGrpc.getUnaryCallMethod().getRequestMarshaller().parse(stream);
                }
            };

    @Test
    void createJsonPrinterWithDefaultSettingsIfNoCustomizerRegistered() throws IOException {
        final GrpcJsonMarshaller jsonMarshaller = GrpcJsonMarshaller.builderForGson().build();
        final String json = serializeToJson(jsonMarshaller);
        assertThat(json)
                .isEqualTo("{\"payload\":{\"type\":\"RANDOM\"},\"fillUsername\":true}");
    }

    @Test
    void createJsonPrinterWithCustomizer() throws IOException {
        final GrpcJsonMarshaller jsonMarshaller = GrpcJsonMarshaller.builderForGson()
                .jsonPrinterCustomizer(JsonFormat.Printer::preservingProtoFieldNames)
                .jsonPrinterCustomizer(JsonFormat.Printer::printingEnumsAsInts)
                .build();
        final String json = serializeToJson(jsonMarshaller);
        assertThat(json)
                .isEqualTo("{\"payload\":{\"type\":2},\"fill_username\":true}");
    }

    @Test
    void createJsonParserWithDefaultSettingsIfNoCustomizerRegistered() throws IOException {
        final GrpcJsonMarshaller jsonMarshaller = GrpcJsonMarshaller.builderForGson().build();
        assertThat(parseJson(jsonMarshaller, "{\"test\": true,\"fill_username\":true}").getFillUsername())
                .isEqualTo(true);
    }

    @Test
    void createJsonParserWithCustomizerNotIgnoringUnknownFields() {
        final GrpcJsonMarshaller jsonMarshaller = GrpcJsonMarshaller.builderForGson()
                .jsonParserCustomizer(parser -> {
                    return parser.usingTypeRegistry(
                            JsonFormat.TypeRegistry.newBuilder()
                                    .add(Messages.SimpleRequest.getDescriptor())
                                    .build()
                    );
                })
                .jsonParserCustomizer(parser -> {
                    return JsonFormat.parser();
                })
                .build();
        assertThatThrownBy(() -> parseJson(jsonMarshaller, "{\"test\": true}"))
                .hasMessageStartingWith("Cannot find field");
    }

    private static String serializeToJson(GrpcJsonMarshaller jsonMarshaller) throws IOException {
        final TestableByteArrayOutputStream outputStream = new TestableByteArrayOutputStream();
        jsonMarshaller.serializeMessage(customRequestMarshaller, testData, outputStream);
        return outputStream.toString();
    }

    private static Messages.SimpleRequest parseJson(
            GrpcJsonMarshaller jsonMarshaller, String input
    ) throws IOException {
        return jsonMarshaller.deserializeMessage(customRequestMarshaller, new StringInputStream(input));
    }
}
