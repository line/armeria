/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
 *
 */

package com.linecorp.armeria.server.athenz;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.athenz.MinifiedAuthZpeClient.AccessCheckStatus;

/**
 * Decorates an {@link HttpService} to check access permissions using Athenz policies.
 *
 * <p>Example:
 * <pre>{@code
 *  import com.linecorp.armeria.client.athenz.ZtsBaseClient;
 *  import com.linecorp.armeria.server.athenz.AthenzService;
 *
 *  ZtsBaseClient ztsBaseClient =
 *    ZtsBaseClient
 *      .builder("https://athenz.example.com:8443/zts/v1")
 *      .keyPair("/var/lib/athenz/service.key.pem", "/var/lib/athenz/service.cert.pem")
 *      .build();
 *
 *  ServerBuilder sb = Server.builder();
 *  // Decorate the service to check access permissions for the "/users" resource.
 *  sb.decorator("/users",
 *               AthenzService
 *                 .builder(ztsBaseClient)
 *                 .action("read")
 *                 .resource("users")
 *                 .policyConfig(new AthenzPolicyConfig("my-domain"))
 *                 .newDecorator());
 * }</pre>
 */
@UnstableApi
public final class AthenzService extends SimpleDecoratingHttpService {

    /**
     * Returns a new {@link AthenzServiceBuilder} with the specified {@link ZtsBaseClient}.
     */
    public static AthenzServiceBuilder builder(ZtsBaseClient ztsBaseClient) {
        requireNonNull(ztsBaseClient, "ztsBaseClient");
        return new AthenzServiceBuilder(ztsBaseClient);
    }

    private final MinifiedAuthZpeClient authZpeClient;
    private final String athenzResource;
    private final String athenzAction;
    private final List<TokenType> tokenTypes;

    AthenzService(HttpService delegate, MinifiedAuthZpeClient authZpeClient,
                  String athenzResource, String athenzAction, List<TokenType> tokenTypes) {
        super(delegate);

        this.authZpeClient = authZpeClient;
        this.athenzResource = athenzResource;
        this.athenzAction = athenzAction;
        this.tokenTypes = tokenTypes;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final String token = extractToken(req.headers());
        if (token == null) {
            return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.PLAIN_TEXT, "Missing token");
        }

        final CompletableFuture<HttpResponse> future = CompletableFuture.supplyAsync(() -> {
            final AccessCheckStatus status = authZpeClient.allowAccess(token, athenzResource, athenzAction);
            if (status == AccessCheckStatus.ALLOW) {
                try {
                    return unwrap().serve(ctx, req);
                } catch (Exception e) {
                    return Exceptions.throwUnsafely(e);
                }
            } else {
                return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.PLAIN_TEXT, status.toString());
            }
        }, ctx.blockingTaskExecutor());
        return HttpResponse.of(future);
    }

    @Nullable
    private String extractToken(RequestHeaders headers) {
        for (TokenType tokenType : tokenTypes) {
            final String token = headers.get(tokenType.headerName(), "");
            if (token.isEmpty()) {
                continue;
            }
            return token;
        }
        return null;
    }
}
