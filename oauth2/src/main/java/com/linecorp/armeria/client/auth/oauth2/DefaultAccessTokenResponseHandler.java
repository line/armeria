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

package com.linecorp.armeria.client.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SCOPE;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.auth.oauth2.GrantedOAuth2AccessToken;
import com.linecorp.armeria.internal.common.auth.oauth2.AbstractOAuth2ResponseHandler;

final class DefaultAccessTokenResponseHandler extends AbstractOAuth2ResponseHandler<GrantedOAuth2AccessToken> {

    static final DefaultAccessTokenResponseHandler INSTANCE = new DefaultAccessTokenResponseHandler();

    @Override
    protected GrantedOAuth2AccessToken handleOkResult(AggregatedHttpResponse response,
                                                      QueryParams requestParams) {
        return GrantedOAuth2AccessToken.parse(response.contentUtf8(), requestParams.get(SCOPE));
    }
}
