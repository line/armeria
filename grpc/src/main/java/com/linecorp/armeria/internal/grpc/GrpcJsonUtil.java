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

package com.linecorp.armeria.internal.grpc;

import java.util.List;
import java.util.Optional;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.protobuf.Message;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;

/**
 * Utilities for dealing with JSON marshalling in server/client.
 */
public final class GrpcJsonUtil {

    /**
     * Returns a {@link MessageMarshaller} with the request/response {@link Message}s of all the {@code methods}
     * registered.
     */
    public static MessageMarshaller jsonMarshaller(List<MethodDescriptor<?, ?>> methods) {
        MessageMarshaller.Builder builder = MessageMarshaller.builder()
                                                             .omittingInsignificantWhitespace(true)
                                                             .ignoringUnknownFields(true);
        for (MethodDescriptor<?, ?> method : methods) {
            marshallerPrototype(method.getRequestMarshaller()).ifPresent(builder::register);
            marshallerPrototype(method.getResponseMarshaller()).ifPresent(builder::register);
        }
        return builder.build();
    }

    private static Optional<Message> marshallerPrototype(Marshaller<?> marshaller) {
        if (marshaller instanceof PrototypeMarshaller) {
            Object prototype = ((PrototypeMarshaller) marshaller).getMessagePrototype();
            if (prototype instanceof Message) {
                return Optional.of((Message) prototype);
            }
        }
        return Optional.empty();
    }

    private GrpcJsonUtil() {}
}
