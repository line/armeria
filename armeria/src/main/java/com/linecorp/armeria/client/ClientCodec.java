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
package com.linecorp.armeria.client;

import java.lang.reflect.Method;
import java.util.Optional;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Promise;

/**
 * Encodes an invocation into a {@link ByteBuf} and decodes its result ({@link ByteBuf}) into a Java object.
 */
public interface ClientCodec {

    /**
     * Invoked when a {@link RemoteInvoker} prepares to perform an invocation.
     */
    <T> void prepareRequest(Method method, Object[] args, Promise<T> resultPromise);

    /**
     * Encodes a Java method invocation into a {@link ServiceInvocationContext}.
     */
    EncodeResult encodeRequest(Channel channel, SessionProtocol sessionProtocol, Method method, Object[] args);

    /**
     * Decodes the response bytes into a Java object.
     */
    <T> T decodeResponse(ServiceInvocationContext ctx, ByteBuf content, Object originalResponse)
            throws Exception;

    /**
     * Returns {@code true} if the invocation result that will be returned to calling code asynchronously.
     */
    boolean isAsyncClient();

    /**
     * The result of {@link #encodeRequest(Channel, SessionProtocol, Method, Object[]) ClientCodec.encodeRequest()}.
     */
    interface EncodeResult {
        /**
         * Returns {@code true} if and only if the encode request has been successful.
         */
        boolean isSuccess();

        /**
         * Returns the {@link ServiceInvocationContext} created as the result of
         * {@link ClientCodec#decodeResponse(ServiceInvocationContext, ByteBuf, Object)
         * ClientCodec.decodeResponse()}
         *
         * @throws IllegalStateException if the encoding was not successful
         */
        ServiceInvocationContext invocationContext();

        /**
         * Returns the content resulting from encoding the request.
         *
         * @throws IllegalStateException if the encoding was not successful
         */
        Object content();

        /**
         * Returns the cause of the encode failure.
         *
         * @throws IllegalStateException if the encoding is successful and thus there is no error to report
         */
        Throwable cause();

        /**
         * Returns the host name of the invocation.
         */
        Optional<String> encodedHost();

        /**
         * Returns the path of the invocation.
         */
        Optional<String> encodedPath();

        /**
         * Returns the {@link Scheme} of the invocation.
         */
        Optional<Scheme> encodedScheme();
    }
}
