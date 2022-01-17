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

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.curioswitch.common.protobuf.json.MessageMarshaller.Builder;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.grpc.DefaultJsonMarshaller;
import com.linecorp.armeria.internal.common.grpc.GrpcJsonUtil;
import com.linecorp.armeria.internal.common.grpc.UpstreamJsonMarshaller;

import io.grpc.ServiceDescriptor;

/**
 * A builder for creating a new {@link GrpcJsonMarshaller} that serializes and deserializes a {@link Message}
 * to and from JSON.
 */
public final class GrpcJsonMarshallerBuilder {

    @Nullable
    private Consumer<MessageMarshaller.Builder> jsonMarshallerCustomizer;

    private GrpcJsonMarshallType grpcJsonMarshallType = GrpcJsonMarshallType.DEFAULT;

    GrpcJsonMarshallerBuilder() {}

    /**
     * Sets a {@link Consumer} that can customize the JSON marshaller for {@link Message} used when handling
     * JSON payloads in the service. This is commonly used to switch from the default of using lowerCamelCase
     * for field names to using the field name from the proto definition, by setting
     * {@link MessageMarshaller.Builder#preservingProtoFieldNames(boolean)}.
     */
    public GrpcJsonMarshallerBuilder jsonMarshallerCustomizer(
            Consumer<? super MessageMarshaller.Builder> jsonMarshallerCustomizer) {
        requireNonNull(jsonMarshallerCustomizer, "jsonMarshallerCustomizer");
        if (this.jsonMarshallerCustomizer == null) {
            @SuppressWarnings("unchecked")
            final Consumer<Builder> cast = (Consumer<Builder>) jsonMarshallerCustomizer;
            this.jsonMarshallerCustomizer = cast;
        } else {
            this.jsonMarshallerCustomizer = this.jsonMarshallerCustomizer.andThen(jsonMarshallerCustomizer);
        }
        return this;
    }

    /**
     * Specifies the {@link GrpcJsonMarshallType} that should be created.
     */
    public GrpcJsonMarshallerBuilder grpcJsonMarshallType(GrpcJsonMarshallType grpcJsonMarshallType) {
        this.grpcJsonMarshallType = requireNonNull(grpcJsonMarshallType);
        return this;
    }

    /**
     * Returns a newly-created {@link GrpcJsonMarshaller} with the specified {@link ServiceDescriptor}.
     */
    public GrpcJsonMarshaller build(ServiceDescriptor serviceDescriptor) {
        requireNonNull(serviceDescriptor, "serviceDescriptor");
        if (grpcJsonMarshallType == GrpcJsonMarshallType.DEFAULT) {
            return new DefaultJsonMarshaller(serviceDescriptor, jsonMarshallerCustomizer);
        } else if (grpcJsonMarshallType == GrpcJsonMarshallType.PROTOBUF_JACKSON) {
            return GrpcJsonUtil.protobufJacksonJsonMarshaller(serviceDescriptor, jsonMarshallerCustomizer);
        } else if (grpcJsonMarshallType == GrpcJsonMarshallType.UPSTREAM) {
            return UpstreamJsonMarshaller.INSTANCE;
        } else {
            throw new Error();
        }
    }
}
