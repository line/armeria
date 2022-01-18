/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.grpc;

import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.grpc.GsonGrpcJsonMarshaller;

/**
 * A builder for creating a new {@link GrpcJsonMarshaller} that serializes and deserializes a {@link Message}
 * to and from JSON.
 */
public final class GsonGrpcJsonMarshallerBuilder {

    @Nullable
    private Consumer<JsonFormat.Parser> jsonParserCustomizer;

    @Nullable
    private Consumer<JsonFormat.Printer> jsonPrinterCustomizer;

    GsonGrpcJsonMarshallerBuilder() {}

    /**
     * Sets a {@link Consumer} that can customize the {@link JsonFormat.Parser} for {@link Message}
     * used when handling JSON payloads in the service.
     */
    public GsonGrpcJsonMarshallerBuilder jsonParserCustomizer(
            Consumer<? super JsonFormat.Parser> jsonParserCustomizer) {
        requireNonNull(jsonParserCustomizer, "jsonParserCustomizer");
        if (this.jsonParserCustomizer == null) {
            @SuppressWarnings("unchecked")
            final Consumer<JsonFormat.Parser> cast = (Consumer<JsonFormat.Parser>) jsonParserCustomizer;
            this.jsonParserCustomizer = cast;
        } else {
            this.jsonParserCustomizer = this.jsonParserCustomizer.andThen(jsonParserCustomizer);
        }
        return this;
    }

    /**
     * Sets a {@link Consumer} that can customize the {@link JsonFormat.Printer} for {@link Message}
     * used when handling JSON payloads in the service.
     */
    public GsonGrpcJsonMarshallerBuilder jsonPrinterCustomizer(
            Consumer<? super JsonFormat.Printer> jsonPrinterCustomizer) {
        requireNonNull(jsonPrinterCustomizer, "jsonPrinterCustomizer");
        if (this.jsonPrinterCustomizer == null) {
            @SuppressWarnings("unchecked")
            final Consumer<JsonFormat.Printer> cast = (Consumer<JsonFormat.Printer>) jsonPrinterCustomizer;
            this.jsonPrinterCustomizer = cast;
        } else {
            this.jsonPrinterCustomizer = this.jsonPrinterCustomizer.andThen(jsonPrinterCustomizer);
        }
        return this;
    }

    /**
     * Returns a newly-created {@link GsonGrpcJsonMarshaller}.
     */
    public GrpcJsonMarshaller build() {
        final JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
        if (jsonPrinterCustomizer != null) {
            jsonPrinterCustomizer.accept(printer);
        }

        final JsonFormat.Parser parser = JsonFormat.parser().ignoringUnknownFields();
        if (jsonParserCustomizer != null) {
            jsonParserCustomizer.accept(parser);
        }
        return new GsonGrpcJsonMarshaller(printer, parser);
    }
}
