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

package com.linecorp.armeria.internal.common.grpc;

import java.util.List;
import java.util.function.Consumer;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.curioswitch.common.protobuf.json.MessageMarshaller.Builder;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;
import io.grpc.ServiceDescriptor;

/**
 * Utilities for dealing with JSON marshalling in server/client.
 */
public final class GrpcJsonUtil {

    /**
     * Returns a {@link MessageMarshaller} with the request/response {@link Message}s of all the {@code methods}
     * registered.
     */
    private static MessageMarshaller jsonMarshaller(
            List<MethodDescriptor<?, ?>> methods,
            @Nullable Consumer<MessageMarshaller.Builder> jsonMarshallerCustomizer) {
        final MessageMarshaller.Builder builder = MessageMarshaller.builder()
                                                                   .omittingInsignificantWhitespace(true)
                                                                   .ignoringUnknownFields(true);
        for (MethodDescriptor<?, ?> method : methods) {
            final Message reqPrototype = marshallerPrototype(method.getRequestMarshaller());
            final Message resPrototype = marshallerPrototype(method.getResponseMarshaller());
            if (reqPrototype != null) {
                builder.register(reqPrototype);
            }
            if (resPrototype != null) {
                builder.register(resPrototype);
            }
        }

        if (jsonMarshallerCustomizer != null) {
            jsonMarshallerCustomizer.accept(builder);
        }

        return builder.build();
    }

    @Nullable
    private static Message marshallerPrototype(Marshaller<?> marshaller) {
        if (marshaller instanceof PrototypeMarshaller) {
            final Object prototype = ((PrototypeMarshaller<?>) marshaller).getMessagePrototype();
            if (prototype instanceof Message) {
                return (Message) prototype;
            }
        }
        return null;
    }

    public static ProtobufJacksonJsonMarshaller protobufJacksonJsonMarshaller(
            ServiceDescriptor serviceDescriptor, @Nullable Consumer<Builder> jsonMarshallerCustomizer) {
        return new ProtobufJacksonJsonMarshaller(
                jsonMarshaller(ImmutableList.copyOf(serviceDescriptor.getMethods()),
                               jsonMarshallerCustomizer));
    }

    private GrpcJsonUtil() {}
}
