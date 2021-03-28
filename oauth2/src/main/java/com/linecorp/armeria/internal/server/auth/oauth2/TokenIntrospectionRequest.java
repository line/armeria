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

package com.linecorp.armeria.internal.server.auth.oauth2;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor;
import com.linecorp.armeria.internal.common.auth.oauth2.AbstractTokenOperationRequest;

/**
 * Implements Token Introspection request/response flow,
 * as per <a href="https://datatracker.ietf.org/doc/html/rfc7662#section-2">[RFC7662], Section 2</a>.
 */
@UnstableApi
public final class TokenIntrospectionRequest extends AbstractTokenOperationRequest<OAuth2TokenDescriptor> {

    /**
     * Implements Token Introspection request/response flow,
     * as per<a href="https://datatracker.ietf.org/doc/html/rfc7662#section-2">[RFC7662], Section 2</a>.
     *
     * @param introspectionEndpoint A {@link WebClient} to facilitate the Token Introspection request. Must
     *                              correspond to the Token Introspection endpoint of the OAuth 2 system.
     * @param introspectionEndpointPath A URI path that corresponds to the Token Introspection endpoint of
     *                                  the OAuth 2 system.
     * @param clientAuthorization Provides client authorization for the OAuth requests,
     *                            as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     */
    public TokenIntrospectionRequest(WebClient introspectionEndpoint, String introspectionEndpointPath,
                                     @Nullable ClientAuthorization clientAuthorization) {
        super(introspectionEndpoint, introspectionEndpointPath, clientAuthorization);
    }

    /**
     * Extracts data from Token Introspection OK response and converts it to the target
     * type {@code TokenDescriptor}.
     */
    @Override
    protected OAuth2TokenDescriptor extractOkResults(AggregatedHttpResponse response,
                                                     QueryParams requestFormData) {
        return OAuth2TokenDescriptor.parse(response.contentUtf8());
    }
}
