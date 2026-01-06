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

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.api.HttpRule;
import com.google.common.base.MoreObjects;

final class DefaultHttpJsonTranscodingOptions implements HttpJsonTranscodingOptions {

    static final HttpJsonTranscodingOptions DEFAULT = HttpJsonTranscodingOptions.builder().build();

    private final boolean ignoreProtoHttpRule;
    private final List<HttpRule> additionalHttpRules;
    private final HttpJsonTranscodingConflictStrategy conflictStrategy;
    private final Set<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules;
    private final UnframedGrpcErrorHandler errorHandler;

    DefaultHttpJsonTranscodingOptions(boolean ignoreProtoHttpRule,
                                      List<HttpRule> additionalHttpRules,
                                      HttpJsonTranscodingConflictStrategy conflictStrategy,
                                      Set<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules,
                                      UnframedGrpcErrorHandler errorHandler) {
        this.ignoreProtoHttpRule = ignoreProtoHttpRule;
        this.additionalHttpRules = additionalHttpRules;
        this.conflictStrategy = conflictStrategy;
        this.queryParamMatchRules = queryParamMatchRules;
        this.errorHandler = errorHandler;
    }

    @Override
    public boolean ignoreProtoHttpRule() {
        return ignoreProtoHttpRule;
    }

    @Override
    public List<HttpRule> additionalHttpRules() {
        return additionalHttpRules;
    }

    @Override
    public HttpJsonTranscodingConflictStrategy conflictStrategy() {
        return conflictStrategy;
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
        return ignoreProtoHttpRule == that.ignoreProtoHttpRule() &&
               additionalHttpRules.equals(that.additionalHttpRules()) &&
               conflictStrategy.equals(that.conflictStrategy()) &&
               queryParamMatchRules.equals(that.queryParamMatchRules()) &&
               errorHandler.equals(that.errorHandler());
    }

    @Override
    public int hashCode() {
        return Objects.hash(ignoreProtoHttpRule, additionalHttpRules, conflictStrategy,
                            queryParamMatchRules, errorHandler);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("ignoreProtoHttpRule", ignoreProtoHttpRule)
                          .add("additionalHttpRules", additionalHttpRules)
                          .add("conflictStrategy", conflictStrategy)
                          .add("queryParamMatchRules", queryParamMatchRules)
                          .add("errorHandler", errorHandler)
                          .toString();
    }
}
