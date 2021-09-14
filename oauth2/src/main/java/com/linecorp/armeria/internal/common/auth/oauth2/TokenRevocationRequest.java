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

package com.linecorp.armeria.internal.common.auth.oauth2;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthorization;

/**
 * Implements Token Revocation request/response flow,
 * as per <a href="https://datatracker.ietf.org/doc/rfc7009/">[RFC7009]</a>.
 */
@UnstableApi
public final class TokenRevocationRequest extends AbstractTokenOperationRequest<Boolean> {

    /**
     * Implements Token Revocation request/response flow,
     * as per <a href="https://datatracker.ietf.org/doc/rfc7009/">[RFC7009]</a>.
     *
     * @param revocationEndpoint A {@link WebClient} to facilitate the Token Revocation request. Must correspond
     *                           to the Token Revocation endpoint of the OAuth 2 system.
     * @param revocationEndpointPath A URI path that corresponds to the Token Revocation endpoint of the
     *                               OAuth 2 system.
     * @param clientAuthorization Provides client authorization for the OAuth requests,
     *                            as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     */
    public TokenRevocationRequest(WebClient revocationEndpoint, String revocationEndpointPath,
                                  @Nullable ClientAuthorization clientAuthorization) {
        super(revocationEndpoint, revocationEndpointPath, clientAuthorization);
    }

    /**
     * Extracts data from Token Revocation OK response and converts it to the target
     * type {@code TokenDescriptor}.
     */
    @Override
    protected Boolean extractOkResults(AggregatedHttpResponse response, QueryParams requestFormData) {
        return true;
    }
}
