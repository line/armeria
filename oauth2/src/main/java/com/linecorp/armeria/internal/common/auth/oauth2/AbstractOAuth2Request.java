/*
 * Copyright 2024 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.common.auth.oauth2.OAuth2Request;

public abstract class AbstractOAuth2Request implements OAuth2Request {

    @Nullable
    private final ClientAuthentication clientAuthentication;

    @Nullable
    private QueryParams bodyParams;

    protected AbstractOAuth2Request(@Nullable ClientAuthentication clientAuthentication) {
        this.clientAuthentication = clientAuthentication;
    }

    @Nullable
    @Override
    public final ClientAuthentication clientAuthentication() {
        return clientAuthentication;
    }

    @Override
    public final HttpRequest asHttpRequest(String endpointPath) {
        requireNonNull(endpointPath, "endpointPath");

        // Build headers
        final RequestHeadersBuilder headersBuilder =
                RequestHeaders.builder(HttpMethod.POST, endpointPath)
                              .contentType(MediaType.FORM_DATA);
        if (clientAuthentication != null) {
            clientAuthentication.addAsHeaders(headersBuilder);
        }
        final RequestHeaders headers = headersBuilder.build();

        // Build body
        final QueryParamsBuilder bodyBuilder = QueryParams.builder();
        addBodyParams(bodyBuilder);
        final QueryParams bodyParams = bodyBuilder.build();
        this.bodyParams = bodyParams;

        return HttpRequest.of(headers, HttpData.ofUtf8(bodyParams.toQueryString()));
    }

    @Override
    public final void addBodyParams(QueryParamsBuilder formBuilder) {
        if (clientAuthentication != null) {
            clientAuthentication.addAsBodyParams(formBuilder);
        }
        doAddBodyParams(formBuilder);
    }

    public abstract void doAddBodyParams(QueryParamsBuilder formBuilder);

    @Override
    public QueryParams bodyParams() {
        if (bodyParams != null) {
            // Micro-optimization: return the cached body parameters if available.
            return bodyParams;
        }
        return bodyParams = OAuth2Request.super.bodyParams();
    }
}
