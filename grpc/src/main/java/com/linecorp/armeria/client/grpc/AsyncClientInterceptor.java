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

package com.linecorp.armeria.client.grpc;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.client.grpc.DeferredClientCall;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;

@UnstableApi
@FunctionalInterface
public interface AsyncClientInterceptor extends ClientInterceptor {

    <I, O> CompletableFuture<ClientCall<I, O>> asyncInterceptCall(
            MethodDescriptor<I, O> method, CallOptions callOptions, Channel next);

    @Override
    default <I, O> ClientCall<I, O> interceptCall(
            MethodDescriptor<I, O> method, CallOptions callOptions, Channel next) {
        return new DeferredClientCall<I, O>(asyncInterceptCall(method, callOptions, next));
    }
}
