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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * Decorates a {@link HttpClient} with an OAuth 2.0 Authorization Grant flow.
 */
public class OAuth2Client extends SimpleDecoratingHttpClient {

    /**
     * Creates a new {@link HttpClient} decorator that handles OAuth 2.0 Authorization Grant flow.
     * @param authorizationGrant An {@link OAuth2AuthorizationGrant} implementing specific
     *                           OAuth 2.0 Authorization Grant flow.
     */
    public static Function<? super HttpClient, OAuth2Client> newDecorator(
            OAuth2AuthorizationGrant authorizationGrant) {
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
        return HttpResponse.from(authorizationGrant.getAccessToken().thenApply(accessToken -> {
            // Create a new request with an additional 'Authorization' header
            final HttpRequest newReq =
                req.withHeaders(req.headers().toBuilder()
                    .set(HttpHeaderNames.AUTHORIZATION, accessToken.authorization()) // Bearer mF_9.B5f-4.1JqM
                    .build());
            // Update the ctx.request
            ctx.updateRequest(newReq);
            try {
                return unwrap().execute(ctx, newReq);
            } catch (Exception e) {
                Exceptions.throwUnsafely(Exceptions.peel(e));
                //noinspection ReturnOfNull
                return null; // will never get here
            }
        }));
    }
}
