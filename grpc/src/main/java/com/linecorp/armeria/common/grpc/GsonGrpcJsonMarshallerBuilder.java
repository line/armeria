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
     * Adds a {@link Consumer} that can customize the {@link JsonFormat.Parser}
     * used when deserializing a JSON payload into a {@link Message}.
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
     * Adds a {@link Consumer} that can customize the {@link JsonFormat.Printer}
     * used when serializing a {@link Message} into a JSON payload.
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
     * Returns a newly-created {@link GrpcJsonMarshaller}.
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
