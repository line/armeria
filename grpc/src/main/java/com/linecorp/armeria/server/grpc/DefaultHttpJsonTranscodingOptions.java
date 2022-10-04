/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import java.util.Objects;
import java.util.Set;

import com.google.common.base.MoreObjects;

final class DefaultHttpJsonTranscodingOptions implements HttpJsonTranscodingOptions {

    static final HttpJsonTranscodingOptions DEFAULT = HttpJsonTranscodingOptions.builder().build();

    private final Set<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules;
    private final UnframedGrpcErrorHandler errorHandler;

    DefaultHttpJsonTranscodingOptions(Set<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules,
                                      UnframedGrpcErrorHandler errorHandler) {
        this.queryParamMatchRules = queryParamMatchRules;
        this.errorHandler = errorHandler;
    }

    @Override
    public Set<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules() {
        return queryParamMatchRules;
    }

    @Override
    public UnframedGrpcErrorHandler errorHandler() {
        return errorHandler;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpJsonTranscodingOptions)) {
            return false;
        }
        final HttpJsonTranscodingOptions that = (HttpJsonTranscodingOptions) o;
        return queryParamMatchRules.equals(that.queryParamMatchRules()) &&
               errorHandler.equals(that.errorHandler());
    }

    @Override
    public int hashCode() {
        return Objects.hash(queryParamMatchRules, errorHandler);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("queryParamMatchRules", queryParamMatchRules)
                          .add("errorHandler", errorHandler)
                          .toString();
    }
}
