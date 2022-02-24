/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.common.graphql.protocol;

import java.util.Map;
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultGraphqlRequest implements GraphqlRequest {

    private final String query;
    @Nullable
    private final String operationName;
    private final Map<String, Object> variables;
    private final Map<String, Object> extensions;

    DefaultGraphqlRequest(String query, @Nullable String operationName,
                          Map<String, Object> variables, Map<String, Object> extensions) {
        this.query = query;
        this.operationName = operationName;
        this.variables = variables;
        this.extensions = extensions;
    }

    @Override
    public String query() {
        return query;
    }

    @Override
    public String operationName() {
        return operationName;
    }

    @Override
    public Map<String, Object> variables() {
        return variables;
    }

    @Override
    public Map<String, Object> extensions() {
        return extensions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultGraphqlRequest)) {
            return false;
        }

        final DefaultGraphqlRequest that = (DefaultGraphqlRequest) o;

        return query.equals(that.query) && Objects.equals(operationName, that.operationName) &&
               variables.equals(that.variables) && extensions.equals(that.extensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, operationName, variables, extensions);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("query", query)
                          .add("operationName", operationName)
                          .add("variables", variables)
                          .add("extensions", extensions)
                          .toString();
    }
}
