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

package com.linecorp.armeria.client.auth.oauth2;

import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Decorates a {@link HttpClient} with an OAuth 2.0 Authorization Grant flow.
 */
@UnstableApi
public final class OAuth2Client extends SimpleDecoratingHttpClient {

    /**
     * Creates a new {@link HttpClient} decorator that handles OAuth 2.0 Authorization Grant flow.
     * @param authorizationGrant An {@link OAuth2AuthorizationGrant} implementing specific
     *                           OAuth 2.0 Authorization Grant flow.
     */
    public static Function<? super HttpClient, OAuth2Client> newDecorator(
            OAuth2AuthorizationGrant authorizationGrant) {
        requireNonNull(authorizationGrant, "authorizationGrant");
        return delegate -> new OAuth2Client(delegate, authorizationGrant);
    }

    private final OAuth2AuthorizationGrant authorizationGrant;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient} with
     * an OAuth 2.0 Authorization Grant flow.
     */
    OAuth2Client(HttpClient delegate, OAuth2AuthorizationGrant authorizationGrant) {
        super(delegate);
        this.authorizationGrant = requireNonNull(authorizationGrant, "authorizationGrant");
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CompletionStage<HttpResponse> future =
                authorizationGrant.getAccessToken().handle((token, cause) -> {
                    if (cause != null) {
                        ctx.logBuilder().endRequest(cause);
                        ctx.logBuilder().endResponse(cause);
                        return HttpResponse.ofFailure(cause);
                    }

                    final HttpRequest newReq = req.withHeaders(req.headers().toBuilder().set(
                            HttpHeaderNames.AUTHORIZATION, token.authorization()).build());
                    ctx.updateRequest(newReq);
                    return executeWithFallback(unwrap(), ctx,
                                               (context, cause0) -> HttpResponse.ofFailure(cause0));
                });
        return HttpResponse.of(future);
    }
}
