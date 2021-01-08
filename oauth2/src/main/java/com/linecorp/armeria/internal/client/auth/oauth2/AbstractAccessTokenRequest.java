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

package com.linecorp.armeria.internal.client.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SCOPE;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.internal.common.auth.oauth2.AbstractOAuth2Request;

/**
 * A common abstraction for the requests implementing various Access Token request/response flows,
 * as per <a href="https://tools.ietf.org/html/rfc6749">[RFC6749]</a>.
 */
@UnstableApi
public abstract class AbstractAccessTokenRequest extends AbstractOAuth2Request<GrantedOAuth2AccessToken> {

    /**
     * A common abstraction for the requests implementing various Access Token request/response flows,
     * as per <a href="https://tools.ietf.org/html/rfc6749">[RFC6749]</a>.
     *
     * @param accessTokenEndpoint A {@link WebClient} to facilitate an Access Token request. Must correspond to
     *                            the Access Token endpoint of the OAuth 2 system.
     * @param accessTokenEndpointPath A URI path that corresponds to the Access Token endpoint of the
     *                                OAuth 2 system.
     * @param clientAuthorization Provides client authorization for the OAuth requests,
     *                            as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     */
    protected AbstractAccessTokenRequest(WebClient accessTokenEndpoint, String accessTokenEndpointPath,
                                         @Nullable ClientAuthorization clientAuthorization) {
        super(accessTokenEndpoint, accessTokenEndpointPath, clientAuthorization);
    }

    /**
     * Extracts data from Access Token OK response and converts it to the target
     * type {@link GrantedOAuth2AccessToken}.
     */
    @Override
    protected GrantedOAuth2AccessToken extractOkResults(AggregatedHttpResponse response,
                                                        QueryParams requestFormData) {
        // if scope was added to the request the response may not include the scope
        // in such case - use the requested scope for the token
        final String scope = requestFormData.get(SCOPE);
        return GrantedOAuth2AccessToken.parse(response.contentUtf8(), scope);
    }
}
