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
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A builder for creating a new {@link GrpcJsonMarshaller} that serializes and deserializes a {@link Message}
 * to and from JSON.
 */
public final class GsonGrpcJsonMarshallerBuilder {
    private static final Logger logger = LoggerFactory.getLogger(GsonGrpcJsonMarshallerBuilder.class);

    private static boolean loggedJsonParserCustomizerWarning;
    private static boolean loggedJsonPrinterCustomizerWarning;

    @Nullable
    private Function<JsonFormat.Parser, JsonFormat.Parser> jsonParserCustomizer;

    @Nullable
    private Function<JsonFormat.Printer, JsonFormat.Printer> jsonPrinterCustomizer;

    GsonGrpcJsonMarshallerBuilder() {}

    /**
     * Adds a {@link Function} that returns customized the {@link JsonFormat.Parser}
     * used when deserializing a JSON payload into a {@link Message}.
     */
    public GsonGrpcJsonMarshallerBuilder jsonParserCustomizer(
            Function<? super JsonFormat.Parser, JsonFormat.Parser> jsonParserCustomizer) {
        requireNonNull(jsonParserCustomizer, "jsonParserCustomizer");
        if (this.jsonParserCustomizer == null) {
            @SuppressWarnings("unchecked")
            final Function<JsonFormat.Parser, JsonFormat.Parser> cast =
                    (Function<JsonFormat.Parser, JsonFormat.Parser>) jsonParserCustomizer;
            this.jsonParserCustomizer = cast;
        } else {
            this.jsonParserCustomizer = this.jsonParserCustomizer.andThen(jsonParserCustomizer);
        }
        return this;
    }

    /**
     * Adds a {@link Consumer} that can customize the {@link JsonFormat.Parser}
     * used when deserializing a JSON payload into a {@link Message}.
     *
     * @deprecated {@link JsonFormat.Parser} is immutable so all changes applied in the {@link Consumer}
     *     will be lost. Please use the {@link #jsonParserCustomizer(Function) jsonParserCustomizer}
     *     which accepts {@link Function} parameter instead.
     */
    @Deprecated
    public GsonGrpcJsonMarshallerBuilder jsonParserCustomizer(
            Consumer<? super JsonFormat.Parser> jsonParserCustomizer) {
        if (!loggedJsonParserCustomizerWarning) {
            logger.warn("{}.jsonParserCustomizer(Consumer) does not work as expected, " +
                        "use jsonParserCustomizer(Function).",
                        getClass().getSimpleName());
            loggedJsonParserCustomizerWarning = true;
        }
        requireNonNull(jsonParserCustomizer, "jsonParserCustomizer");
        if (this.jsonParserCustomizer == null) {
            @SuppressWarnings("unchecked")
            final Consumer<JsonFormat.Parser> cast = (Consumer<JsonFormat.Parser>) jsonParserCustomizer;
            this.jsonParserCustomizer = parser -> {
                cast.accept(parser);
                return parser;
            };
        } else {
            this.jsonParserCustomizer = this.jsonParserCustomizer.andThen(parser -> {
                jsonParserCustomizer.accept(parser);
                return parser;
            });
        }
        return this;
    }

    /**
     * Adds a {@link Consumer} that can customize the {@link JsonFormat.Printer}
     * used when serializing a {@link Message} into a JSON payload.
     *
     * @deprecated {@link JsonFormat.Printer} is immutable so all changes applied in the {@link Consumer}
     *     will be lost. Please use the {@link #jsonPrinterCustomizer(Function) jsonParserCustomizer}
     *     which accepts {@link Function} parameter instead.
     */
    @Deprecated
    public GsonGrpcJsonMarshallerBuilder jsonPrinterCustomizer(
            Consumer<? super JsonFormat.Printer> jsonPrinterCustomizer) {
        if (!loggedJsonPrinterCustomizerWarning) {
            logger.warn("{}.jsonPrinterCustomizer(Consumer) does not work as expected; " +
                        "use jsonPrinterCustomizer(Function).",
                        getClass().getSimpleName());
            loggedJsonPrinterCustomizerWarning = true;
        }

        requireNonNull(jsonPrinterCustomizer, "jsonPrinterCustomizer");
        if (this.jsonPrinterCustomizer == null) {
            @SuppressWarnings("unchecked")
            final Consumer<JsonFormat.Printer> cast = (Consumer<JsonFormat.Printer>) jsonPrinterCustomizer;
            this.jsonPrinterCustomizer = printer -> {
                cast.accept(printer);
                return printer;
            };
        } else {
            this.jsonPrinterCustomizer = this.jsonPrinterCustomizer.andThen(printer -> {
                jsonPrinterCustomizer.accept(printer);
                return printer;
            });
        }
        return this;
    }

    /**
     * Adds a {@link Function} that returns customized the {@link JsonFormat.Printer}
     * used when serializing a {@link Message} into a JSON payload.
     */
    public GsonGrpcJsonMarshallerBuilder jsonPrinterCustomizer(
            Function<? super JsonFormat.Printer, JsonFormat.Printer> jsonPrinterCustomizer) {
        requireNonNull(jsonPrinterCustomizer, "jsonPrinterCustomizer");
        if (this.jsonPrinterCustomizer == null) {
            @SuppressWarnings("unchecked")
            final Function<JsonFormat.Printer, JsonFormat.Printer> cast =
                    (Function<JsonFormat.Printer, JsonFormat.Printer>) jsonPrinterCustomizer;
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
        JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
        if (jsonPrinterCustomizer != null) {
            printer = jsonPrinterCustomizer.apply(printer);
        }

        JsonFormat.Parser parser = JsonFormat.parser().ignoringUnknownFields();
        if (jsonParserCustomizer != null) {
            parser = jsonParserCustomizer.apply(parser);
        }
        return new GsonGrpcJsonMarshaller(printer, parser);
    }
}
