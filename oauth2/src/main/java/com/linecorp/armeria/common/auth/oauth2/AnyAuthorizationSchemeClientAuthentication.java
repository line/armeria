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

package com.linecorp.armeria.common.auth.oauth2;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.internal.common.auth.oauth2.CaseUtil;

final class AnyAuthorizationSchemeClientAuthentication implements ClientAuthentication {

    private final String headerValue;
    private final String authorizationScheme;

    AnyAuthorizationSchemeClientAuthentication(String authorizationScheme, String authorization) {
        headerValue = CaseUtil.firstUpperAllLowerCase(authorizationScheme) + ' ' + authorization;
        this.authorizationScheme = authorizationScheme;
    }

    @Override
    public void addAsHeaders(HttpHeadersBuilder headersBuilder) {
        headersBuilder.add(HttpHeaderNames.AUTHORIZATION, headerValue);
    }

    @Override
    public void addAsBodyParams(QueryParamsBuilder formBuilder) {}

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AnyAuthorizationSchemeClientAuthentication)) {
            return false;
        }
        final AnyAuthorizationSchemeClientAuthentication that = (AnyAuthorizationSchemeClientAuthentication) o;
        return Objects.equals(headerValue, that.headerValue);
    }

    @Override
    public int hashCode() {
        return headerValue.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("authorizationScheme", authorizationScheme)
                          .add("authorization", "****")
                          .toString();
    }
}
