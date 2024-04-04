/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.Set;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;

public final class UnframedGrpcErrorHandlerBuilder {
    private UnframedGrpcStatusMappingFunction statusMappingFunction;

    private MessageMarshaller jsonMarshaller = UnframedGrpcErrorHandlers.ERROR_DETAILS_MARSHALLER;

    @Nullable
    private Set<UnframedGrpcErrorResponseType> responseTypes;

    UnframedGrpcErrorHandlerBuilder(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        this.statusMappingFunction = statusMappingFunction;
    }

    public UnframedGrpcErrorHandlerBuilder jsonMarshaller(MessageMarshaller jsonMarshaller) {
        this.jsonMarshaller = requireNonNull(jsonMarshaller, "jsonMarshaller");
        return this;
    }

    public UnframedGrpcErrorHandlerBuilder statusMappingFunction(
            UnframedGrpcStatusMappingFunction statusMappingFunction) {
        this.statusMappingFunction = requireNonNull(statusMappingFunction, "statusMappingFunction");
        return this;
    }

    public UnframedGrpcErrorHandlerBuilder responseTypes(UnframedGrpcErrorResponseType... responseTypes) {
        requireNonNull(responseTypes, "responseTypes");
        if (this.responseTypes == null) {
            this.responseTypes = EnumSet.noneOf(UnframedGrpcErrorResponseType.class);
        }
        this.responseTypes.addAll(ImmutableSet.copyOf(responseTypes));
        return this;
    }

    public UnframedGrpcErrorHandlerBuilder registerMarshallers(Message... messages) {
        requireNonNull(messages, "messages");
        MessageMarshaller.Builder jsonMarshallerBuilder = jsonMarshaller.toBuilder();

        for (Message message : messages) {
            jsonMarshallerBuilder = jsonMarshallerBuilder.register(message);
        }

        jsonMarshaller = jsonMarshallerBuilder.build();
        return this;
    }

    @SafeVarargs
    public final UnframedGrpcErrorHandlerBuilder registerMarshallers(Class<? extends Message>... messageTypes) {
        requireNonNull(messageTypes, "messageTypes");
        MessageMarshaller.Builder jsonMarshallerBuilder = jsonMarshaller.toBuilder();

        for (Class<? extends Message> messageType : messageTypes) {
            jsonMarshallerBuilder = jsonMarshallerBuilder.register(messageType);
        }

        jsonMarshaller = jsonMarshallerBuilder.build();
        return this;
    }

    public UnframedGrpcErrorHandlerBuilder registerMarshallers(Iterable<?> messagesOrMessageTypes) {
        requireNonNull(messagesOrMessageTypes, "messagesOrMessageTypes");
        MessageMarshaller.Builder jsonMarshallerBuilder = jsonMarshaller.toBuilder();

        for (final Object messageOrMessageType : messagesOrMessageTypes) {
            requireNonNull(messageOrMessageType, "messagesOrMessageTypes contains null.");
            if(messageOrMessageType instanceof Message) {
                jsonMarshallerBuilder = jsonMarshallerBuilder.register((Message)messageOrMessageType);
            } else {
                throw new IllegalArgumentException(messageOrMessageType.getClass().getName() +
                                                   " is neither Message type nor Message extended type.");
            }

        }

        jsonMarshaller = jsonMarshallerBuilder.build();
        return this;
    }

    public UnframedGrpcErrorHandler build() {
        if(responseTypes == ImmutableSet.of(UnframedGrpcErrorResponseType.JSON)) {
            return UnframedGrpcErrorHandlers.ofJson(statusMappingFunction, jsonMarshaller);
        }
        if(responseTypes == ImmutableSet.of(UnframedGrpcErrorResponseType.PLAINTEXT)) {
            return UnframedGrpcErrorHandlers.ofPlaintext(statusMappingFunction);
        }
        return UnframedGrpcErrorHandlers.of(statusMappingFunction, jsonMarshaller);
    }
}
