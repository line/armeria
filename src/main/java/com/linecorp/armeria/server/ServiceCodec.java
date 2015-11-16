/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Promise;

/**
 * Decodes a {@link ByteBuf} into an invocation and encodes the result produced by
 * {@link ServiceInvocationHandler} into a {@link ByteBuf}.
 *
 * @see Service
 */
public interface ServiceCodec {

    /**
     * Invoked when the {@link Service} of this {@link ServiceCodec} has been added to the specified
     * {@link Server}.
     */
    default void codecAdded(Server server) throws Exception {}

    /**
     * Decodes an invocation request.
     */
    DecodeResult decodeRequest(Channel ch, SessionProtocol sessionProtocol,
                               String hostname, String path, String mappedPath,
                               ByteBuf in, Object originalRequest, Promise<Object> promise) throws Exception;

    /**
     * Returns {@code true} if and only if a failed invocation affects the status of its enclosing session
     * protocol response. That is, if this property is {@code false}, the status of the enclosing session
     * protocol response will indicate 'success' even if the invocation has failed.
     */
    boolean failureResponseFailsSession(ServiceInvocationContext ctx);

    /**
     * Encodes a response for a successful invocation.
     */
    ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception;

    /**
     * Encodes a response for a failed invocation.
     */
    ByteBuf encodeFailureResponse(ServiceInvocationContext ctx, Throwable cause) throws Exception;

    /**
     * Undecorates this {@link ServiceCodec} to find the {@link ServiceCodec} which is an instance of the
     * specified {@code codecType}. Use this method instead of an explicit downcast since most
     * {@link ServiceCodec}s are decorated via {@link Service#decorate(Function)} or
     * {@link Service#decorateCodec(Function)} and thus cannot be downcast. For example:
     * <pre>{@code
     * Service s = new MyService().decorate(LoggingService::new).decorate(AuthService::new);
     * MyServiceCodec c1 = s.codec().as(MyServiceCodec.class);
     * LoggingServiceCodec c2 = s.codec().as(LoggingServiceCodec.class);
     * AuthServiceCodec c3 = s.codec().as(AuthServiceCodec.class);
     * }</pre>
     *
     * @param codecType the type of the desired {@link ServiceCodec}
     * @return the {@link ServiceCodec} which is an instance of {@code codecType} if this {@link ServiceCodec}
     *         decorated such a {@link ServiceCodec}. {@link Optional#empty()} otherwise.
     */
    default <T extends ServiceCodec> Optional<T> as(Class<T> codecType) {
        requireNonNull(codecType, "codecType");
        if (codecType.isInstance(this)) {
            return Optional.of(codecType.cast(this));
        }

        return Optional.empty();
    }

    /**
     * Whether
     * {@link #decodeRequest(Channel, SessionProtocol, String, String, String, ByteBuf, Object, Promise)
     * ServiceCodec.decodeRequest()} has succeeded or not.
     */
    enum DecodeResultType {
        /**
         * Decoded successfully.
         * {@link ServiceInvocationHandler#invoke(ServiceInvocationContext, Executor, Promise)} should handle
         * the request.
         */
        SUCCESS,
        /**
         * Failed to decode. The {@link SessionProtocol} layer should reject the request with the proposed
         * {@link DecodeResult#errorResponse()}.
         */
        FAILURE,
        /**
         * Turned out that the {@link Service} has no mapping for the request. The {@link SessionProtocol}
         * layer should reject the request with a 'not found' response.
         */
        NOT_FOUND
    }

    /**
     * The result of
     * {@link #decodeRequest(Channel, SessionProtocol, String, String, String, ByteBuf, Object, Promise)
     * ServiceCodec.decodeRequest()}.
     */
    interface DecodeResult {

        /**
         * The singleton whose {@link #type()} is {@link DecodeResultType#NOT_FOUND}.
         */
        DecodeResult NOT_FOUND = new DefaultDecodeResult();

        /**
         * Returns the type of this result.
         */
        DecodeResultType type();

        /**
         * Returns the {@link ServiceInvocationContext} created as a result of
         * {@link ServiceCodec#decodeRequest(Channel, SessionProtocol, String, String, String, ByteBuf, Object,
         * Promise) ServiceCodec.decodeRequest()}.
         *
         * @throws IllegalStateException if the decode result is not successful
         */
        ServiceInvocationContext invocationContext();

        /**
         * Returns the error response to send to the client.
         *
         * @throws IllegalStateException if the decode result is successful and thus no error response is available
         */
        Object errorResponse();

        /**
         * Returns the cause of the decode failure.
         *
         * @throws IllegalStateException if the decode result is successful and thus there is no error to report
         */
        Throwable cause();

        /**
         * Returns the serialization format of the invocation.
         */
        Optional<SerializationFormat> decodedSerializationFormat();

        /**
         * Returns the ID of the invocation.
         */
        Optional<String> decodedInvocationId();

        /**
         * Returns the name of the method being invoked.
         */
        Optional<String> decodedMethod();

        /**
         * Returns the list of the invocation parameters.
         */
        Optional<List<Object>> decodedParams();
    }

    /**
     * The default {@link DecodeResult} implementation.
     */
    class DefaultDecodeResult implements DecodeResult {

        private final Object value;
        private final Throwable cause;

        /**
         * Creates a new successful result with the specified {@link ServiceInvocationContext}.
         */
        public DefaultDecodeResult(ServiceInvocationContext invocationContext) {
            value = requireNonNull(invocationContext, "invocationContext");
            cause = null;
        }

        /**
         * Creates a new failure result with the specified {@code errorResponse} and {@code cause}.
         */
        public DefaultDecodeResult(Object errorResponse, Throwable cause) {
            value = requireNonNull(errorResponse, "errorResponse");
            this.cause = requireNonNull(cause, "cause");
        }

        /**
         * The special constructor for {@link DecodeResult#NOT_FOUND}
         */
        private DefaultDecodeResult() {
            value = null;
            cause = null;
        }

        @Override
        public final DecodeResultType type() {
            if (cause == null) {
                return value != null ? DecodeResultType.SUCCESS : DecodeResultType.NOT_FOUND;
            } else {
                return DecodeResultType.FAILURE;
            }
        }

        @Override
        public final ServiceInvocationContext invocationContext() {
            if (cause != null) {
                throw new IllegalStateException("An unsuccessful result does not have an invocation context.");
            }

            return (ServiceInvocationContext) value;
        }

        @Override
        public final Object errorResponse() {
            if (cause == null) {
                throw new IllegalStateException("Only a failed result has an error response.");
            }
            return value;
        }

        @Override
        public final Throwable cause() {
            if (cause == null) {
                throw new IllegalStateException("Only a failed result has a cause.");
            }
            return cause;
        }

        @Override
        public Optional<SerializationFormat> decodedSerializationFormat() {
            return Optional.empty();
        }

        @Override
        public Optional<String> decodedInvocationId() {
            return Optional.empty();
        }

        @Override
        public Optional<String> decodedMethod() {
            return Optional.empty();
        }

        @Override
        public Optional<List<Object>> decodedParams() {
            return Optional.empty();
        }
    }
}
