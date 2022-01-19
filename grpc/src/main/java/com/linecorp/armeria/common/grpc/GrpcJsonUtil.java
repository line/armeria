/*
 * Copyright 2017 LINE Corporation
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

import java.util.List;
import java.util.function.Consumer;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;

/**
 * Utilities for dealing with JSON marshalling in server/client.
 */
final class GrpcJsonUtil {

    private static final Logger logger = LoggerFactory.getLogger(GrpcJsonUtil.class);

    /**
     * Returns a {@link MessageMarshaller} with the request/response {@link Message}s of all the {@code methods}
     * registered.
     */
    public static MessageMarshaller jsonMarshaller(
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

        try {
            return builder.build();
        } catch (RuntimeException e) {
            logger.warn("Failed to instantiate a json marshaller for {}. Consider using {}.ofGson() instead.",
                        methods, GrpcJsonMarshaller.class.getName(), e);
            throw e;
        }
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

    private GrpcJsonUtil() {}
}
