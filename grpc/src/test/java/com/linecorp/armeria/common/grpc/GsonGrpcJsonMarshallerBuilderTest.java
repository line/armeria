package com.linecorp.armeria.common.grpc;

import com.google.api.client.testing.util.TestableByteArrayOutputStream;
import com.google.protobuf.util.JsonFormat;
import io.grpc.MethodDescriptor;
import org.apache.tools.ant.filters.StringInputStream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import testing.grpc.Messages;
import testing.grpc.TestServiceGrpc;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

                @Nullable
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
        GrpcJsonMarshaller jsonMarshaller = GrpcJsonMarshaller.builderForGson().build();
        String json = serializeToJson(jsonMarshaller);
        assertThat(json)
                .isEqualTo("{\"payload\":{\"type\":\"RANDOM\"},\"fillUsername\":true}");
    }

    @Test
    void createJsonPrinterWithCustomizer() throws IOException {
        GrpcJsonMarshaller jsonMarshaller = GrpcJsonMarshaller.builderForGson()
                .jsonPrinterCustomizer(JsonFormat.Printer::preservingProtoFieldNames)
                .jsonPrinterCustomizer(JsonFormat.Printer::printingEnumsAsInts)
                .build();
        String json = serializeToJson(jsonMarshaller);
        assertThat(json)
                .isEqualTo("{\"payload\":{\"type\":2},\"fill_username\":true}");
    }

    @Test
    void createJsonParserWithDefaultSettingsIfNoCustomizerRegistered() throws IOException {
        GrpcJsonMarshaller jsonMarshaller = GrpcJsonMarshaller.builderForGson().build();
        assertThat(parseJson(jsonMarshaller, "{\"test\": true,\"fill_username\":true}").getFillUsername())
                .isEqualTo(true);
    }

    @Test
    void createJsonParserWithCustomizerNotIgnoringUnknownFields() {
        GrpcJsonMarshaller jsonMarshaller = GrpcJsonMarshaller.builderForGson()
                .jsonParserCustomizer((parser -> parser.usingTypeRegistry(
                        JsonFormat.TypeRegistry.newBuilder()
                                .add(Messages.SimpleRequest.getDescriptor())
                                .build()
                )))
                .jsonParserCustomizer(parser -> JsonFormat.parser())
                .build();
        assertThatThrownBy(() -> parseJson(jsonMarshaller, "{\"test\": true}"))
                .hasMessageStartingWith("Cannot find field");
    }

    private static String serializeToJson(GrpcJsonMarshaller jsonMarshaller) throws IOException {
        TestableByteArrayOutputStream outputStream = new TestableByteArrayOutputStream();
        jsonMarshaller.serializeMessage(customRequestMarshaller, testData, outputStream);
        return outputStream.toString();
    }

    private static Messages.SimpleRequest parseJson(GrpcJsonMarshaller jsonMarshaller, String input) throws IOException {
        return jsonMarshaller.deserializeMessage(customRequestMarshaller, new StringInputStream(input));
    }
}
