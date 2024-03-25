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

package com.linecorp.armeria.internal.client.grpc;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.common.grpc.MetadataUtil;

import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallCredentials.MetadataApplier;
import io.grpc.CallCredentials.RequestInfo;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;

final class CallCredentialsDecoratingClient extends SimpleDecoratingHttpClient {

    private final CallCredentials credentials;
    private final MethodDescriptor<?, ?> method;
    private final String authority;

    CallCredentialsDecoratingClient(HttpClient delegate, CallCredentials credentials,
                                    MethodDescriptor<?, ?> method, String authority) {
        super(delegate);
        this.credentials = credentials;
        this.method = method;
        this.authority = authority;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) {
        final CompletableFuture<HttpResponse> response = new CompletableFuture<>();

        final RequestInfo requestInfo = new RequestInfo() {
            @Override
            public MethodDescriptor<?, ?> getMethodDescriptor() {
                return method;
            }

            @Override
            public SecurityLevel getSecurityLevel() {
                // Semantics of SecurityLevel aren't very clear but we follow the pattern of the upstream
                // client.
                // https://github.com/grpc/grpc-java/blob/bf2a66c8a2d52be41afd7090c151984a3ce64e0d/okhttp/src/main/java/io/grpc/okhttp/OkHttpClientTransport.java#L586
                return ctx.sessionProtocol().isTls() ? SecurityLevel.PRIVACY_AND_INTEGRITY : SecurityLevel.NONE;
            }

            @Override
            public String getAuthority() {
                return authority;
            }

            @Override
            public Attributes getTransportAttrs() {
                // There is a race condition where the first request to an endpoint will not have transport
                // attributes available yet. It seems unlikely that CallCredentials could ever use these
                // attributes reliably, so for now don't return them and revisit if anyone needs them.

                // The most popular CallCredentials, GoogleAuthLibraryCallCredentials, do not use the transport
                // attributes.
                // https://github.com/grpc/grpc-java/blob/master/auth/src/main/java/io/grpc/auth/GoogleAuthLibraryCallCredentials.java
                return Attributes.EMPTY;
            }
        };

        credentials.applyRequestMetadata(
                requestInfo,
                CommonPools.blockingTaskExecutor(),
                new MetadataApplier() {
                    @Override
                    public void apply(Metadata metadata) {
                        ctx.mutateAdditionalRequestHeaders(
                                headers -> MetadataUtil.fillHeaders(metadata, headers));
                        try {
                            response.complete(unwrap().execute(ctx, req));
                        } catch (Exception e) {
                            response.completeExceptionally(e);
                        }
                    }

                    @Override
                    public void fail(Status status) {
                        response.completeExceptionally(status.asRuntimeException());
                    }
                });

        return HttpResponse.of(response);
    }
}
