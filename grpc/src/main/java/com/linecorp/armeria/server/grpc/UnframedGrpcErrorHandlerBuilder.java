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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Constructs a {@link UnframedGrpcErrorHandler} to handle unframed gRPC errors.
 */
@UnstableApi
public final class UnframedGrpcErrorHandlerBuilder {
    private UnframedGrpcStatusMappingFunction statusMappingFunction = UnframedGrpcStatusMappingFunction.of();

    @Nullable
    private MessageMarshaller jsonMarshaller;

    @Nullable
    private List<Message> marshalledMessages;

    @Nullable
    private List<Class<? extends Message>> marshalledMessageTypes;

    @Nullable
    private Set<UnframedGrpcErrorResponseType> responseTypes;

    UnframedGrpcErrorHandlerBuilder() {}

    /**
     * Sets a custom JSON marshaller to be used by the error handler.
     *
     * <p>This method allows the caller to specify a custom JSON marshaller
     * for encoding the error responses. If messages or message types have
     * already been registered, calling this method will result in an
     * {@link IllegalStateException}. If nothing is specified,
     * the default JSON marshaller is used.
     *
     * @param jsonMarshaller The custom JSON marshaller to use
     */
    public UnframedGrpcErrorHandlerBuilder jsonMarshaller(MessageMarshaller jsonMarshaller) {
        requireNonNull(jsonMarshaller, "jsonMarshaller");
        checkState(marshalledMessages == null && marshalledMessageTypes == null,
                   "Cannot set a custom JSON marshaller because one or more Message instances or " +
                   "Message types have already been registered. To set a custom marshaller, " +
                   "ensure that no Message or Message type registrations have been made before " +
                   "calling this method."
        );
        this.jsonMarshaller = jsonMarshaller;
        return this;
    }

    /**
     * Specifies the status mapping function to be used by the error handler.
     *
     * <p>This function determines how gRPC statuses are mapped to HTTP statuses
     * in the error response.
     *
     * @param statusMappingFunction The status mapping function
     */
    public UnframedGrpcErrorHandlerBuilder statusMappingFunction(
            UnframedGrpcStatusMappingFunction statusMappingFunction) {
        this.statusMappingFunction = requireNonNull(statusMappingFunction, "statusMappingFunction");
        return this;
    }

    /**
     * Specifies the response types that the error handler will support.
     *
     * <p>This method allows specifying one or more response types (e.g., JSON, PLAINTEXT)
     * that the error handler can produce. If nothing is specified or multiple types are specified, the actual
     * response type is determined by the response's content type.
     *
     * @param responseTypes The response types to support
     */
    public UnframedGrpcErrorHandlerBuilder responseTypes(UnframedGrpcErrorResponseType... responseTypes) {
        requireNonNull(responseTypes, "responseTypes");

        if (this.responseTypes == null) {
            this.responseTypes = EnumSet.noneOf(UnframedGrpcErrorResponseType.class);
        }
        Collections.addAll(this.responseTypes, responseTypes);
        return this;
    }

    /**
     * Registers custom messages to be marshalled by the error handler.
     *
     * <p>This method registers specific message instances for custom error responses.
     * If a custom JSON marshaller has already been set, calling this method will
     * result in an {@link IllegalStateException}.
     *
     * @param messages The message instances to register
     */
    public UnframedGrpcErrorHandlerBuilder registerMarshalledMessages(Message... messages) {
        requireNonNull(messages, "messages");
        checkState(jsonMarshaller == null,
                   "Cannot register custom messages because a custom JSON marshaller has already been set. " +
                   "Use the custom marshaller to register custom messages.");

        if (marshalledMessages == null) {
            marshalledMessages = new ArrayList<>();
        }
        Collections.addAll(marshalledMessages, messages);
        return this;
    }

    /**
     * Registers custom messages to be marshalled by the error handler.
     *
     * <p>This method allows registering message instances for custom error responses.
     * If a custom JSON marshaller has already been set, calling this method will
     * result in an {@link IllegalStateException}.
     *
     * @param messages The collection of messages to register
     */
    public UnframedGrpcErrorHandlerBuilder registerMarshalledMessages(
            Iterable<? extends Message> messages) {
        requireNonNull(messages, "messages");
        checkState(jsonMarshaller == null,
                   "Cannot register the collection of messages because a custom JSON marshaller has " +
                   "already been set. Use the custom marshaller to register custom messages.");

        if (marshalledMessages == null) {
            marshalledMessages = new ArrayList<>();
        }
        messages.forEach(marshalledMessages::add);
        return this;
    }

    /**
     * Registers custom message types to be marshalled by the error handler.
     *
     * <p>This method registers specific message types for custom error responses.
     * If a custom JSON marshaller has already been set, calling this method will
     * result in an {@link IllegalStateException}.
     *
     * @param messageTypes The message types to register
     */
    @SafeVarargs
    public final UnframedGrpcErrorHandlerBuilder registerMarshalledMessageTypes(
            Class<? extends Message>... messageTypes) {
        requireNonNull(messageTypes, "messageTypes");
        checkState(jsonMarshaller == null,
                   "Cannot register custom messageTypes because a custom JSON marshaller has already been " +
                   "set. Use the custom marshaller to register custom message types.");

        if (marshalledMessageTypes == null) {
            marshalledMessageTypes = new ArrayList<>();
        }
        Collections.addAll(marshalledMessageTypes, messageTypes);
        return this;
    }

    /**
     * Registers custom message types to be marshalled by the error handler.
     *
     * <p>This method allows registering message instances for custom error responses.
     * If a custom JSON marshaller has already been set, calling this method will
     * result in an {@link IllegalStateException}.
     *
     * @param messageTypes The collection of message types to register
     */
    public UnframedGrpcErrorHandlerBuilder registerMarshalledMessageTypes(
            Iterable<? extends Class<? extends Message>> messageTypes) {
        requireNonNull(messageTypes, "messageTypes");
        checkState(jsonMarshaller == null,
                   "Cannot register the collection of messageTypes because a custom JSON marshaller has " +
                   "already been set. Use the custom marshaller to register custom message types.");

        if (marshalledMessageTypes == null) {
            marshalledMessageTypes = new ArrayList<>();
        }
        messageTypes.forEach(marshalledMessageTypes::add);
        return this;
    }

    /**
     * Returns a newly created {@link UnframedGrpcErrorHandler}.
     *
     * <p>This method constructs a new {@code UnframedGrpcErrorHandler} with the
     * current configuration of this builder.
     */
    public UnframedGrpcErrorHandler build() {
        if (jsonMarshaller == null) {
            jsonMarshaller = JsonUnframedGrpcErrorHandler.ERROR_DETAILS_MARSHALLER;
            final MessageMarshaller.Builder builder = jsonMarshaller.toBuilder();

            if (marshalledMessages != null) {
                for (final Message message : marshalledMessages) {
                    builder.register(message);
                }
            }

            if (marshalledMessageTypes != null) {
                for (final Class<? extends Message> messageType : marshalledMessageTypes) {
                    builder.register(messageType);
                }
            }

            jsonMarshaller = builder.build();
        }

        if (responseTypes == null) {
            return DefaultUnframedGrpcErrorHandler.of(statusMappingFunction, jsonMarshaller);
        }

        if (responseTypes.contains(UnframedGrpcErrorResponseType.JSON) &&
            responseTypes.contains(UnframedGrpcErrorResponseType.PLAINTEXT)) {
            return DefaultUnframedGrpcErrorHandler.of(statusMappingFunction, jsonMarshaller);
        }

        if (responseTypes.contains(UnframedGrpcErrorResponseType.JSON)) {
            return JsonUnframedGrpcErrorHandler.of(statusMappingFunction, jsonMarshaller);
        }

        if (responseTypes.contains(UnframedGrpcErrorResponseType.PLAINTEXT)) {
            return TextUnframedGrpcErrorHandler.of(statusMappingFunction);
        }

        return DefaultUnframedGrpcErrorHandler.of(statusMappingFunction, jsonMarshaller);
    }
}
