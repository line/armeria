/*
 * Copyright 2023 LINE Corporation
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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * A {@link ServerInterceptor} that is able to asynchronously execute the interceptor without blocking the
 * caller thread.
 * For example:
 * <pre>{@code
 * class AuthServerInterceptor implements AsyncServerInterceptor {
 *
 *     @Override
 *     <I, O> CompletableFuture<Listener<I>> asyncInterceptCall(
 *             ServerCall<I, O> call, Metadata headers, ServerCallHandler<I, O> next) {
 *        Context grpcContext = Context.current();
 *        return authorizer.authorize(headers).thenApply(result -> {
 *             if (result) {
 *                 // `next.startCall()` should be wrapped with `grpcContext.call()` if you want to propagate
 *                 // the context to the next interceptor.
 *                 return grpcContext.call(() -> next.startCall(call, headers));
 *             } else {
 *                 throw new AuthenticationException("Invalid access");
 *             }
 *        });
 *    }
 * }
 * }</pre>
 */
@UnstableApi
@FunctionalInterface
public interface AsyncServerInterceptor extends ServerInterceptor {

    /**
     * Asynchronously intercepts {@link ServerCall} dispatch by the {@code next} {@link ServerCallHandler}.
     */
    <I, O> CompletableFuture<Listener<I>> asyncInterceptCall(ServerCall<I, O> call, Metadata headers,
                                                             ServerCallHandler<I, O> next);

    @Override
    default <I, O> Listener<I> interceptCall(ServerCall<I, O> call, Metadata headers,
                                             ServerCallHandler<I, O> next) {
        return new DeferredListener<>(call, asyncInterceptCall(call, headers, next));
    }
}
