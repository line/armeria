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

package com.linecorp.armeria.server.auth.oauth2;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.auth.oauth2.OAuth2TokenDescriptor;
import com.linecorp.armeria.internal.common.auth.oauth2.AbstractOAuth2ResponseHandler;

final class OAuth2TokenIntrospectionResponseHandler
        extends AbstractOAuth2ResponseHandler<OAuth2TokenDescriptor> {

    static final OAuth2TokenIntrospectionResponseHandler INSTANCE =
            new OAuth2TokenIntrospectionResponseHandler();

    @Override
    protected OAuth2TokenDescriptor handleOkResult(AggregatedHttpResponse response, QueryParams requestParams) {
        return OAuth2TokenDescriptor.parse(response.contentUtf8());
    }
}
