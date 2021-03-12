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

package com.linecorp.armeria.server.graphql;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Response;

import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;

/**
 * A output of a GraphQL query over HTTP.
 */
public final class GraphQLOutput implements Response, ExecutionResult {

    private final ExecutionResult executionResult;

    /**
     * Returns new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns new {@link GraphQLOutput} instance.
     */
    public static GraphQLOutput of(ExecutionResult executionResult) {
        return new Builder(executionResult).build();
    }

    private GraphQLOutput(ExecutionResult executionResult) {
        this.executionResult = requireNonNull(executionResult, "executionResult");
    }

    /**
     * Returns the {@link GraphQLError}s.
     */
    @Override
    public List<GraphQLError> getErrors() {
        return executionResult.getErrors();
    }

    /**
     * Returns the data.
     */
    @Override
    public <T> T getData() {
        return executionResult.getData();
    }

    /**
     * Returns true if the entry "data" should be present in the result false otherwise.
     */
    @Override
    public boolean isDataPresent() {
        return executionResult.isDataPresent();
    }

    /**
     * Returns the extensions.
     */
    @Override
    public Map<Object, Object> getExtensions() {
        return executionResult.getExtensions();
    }

    /**
     * Converts to specification.
     */
    @Override
    public Map<String, Object> toSpecification() {
        return executionResult.toSpecification();
    }

    /**
     * Transforms this instance through a {@link Builder} and
     * return a new instance with the modified values.
     */
    public GraphQLOutput transform(Consumer<Builder> consumer) {
        final Builder builder = new Builder(this);
        consumer.accept(builder);
        return builder.build();
    }

    /**
     * Builds a new {@link GraphQLOutput}.
     */
    public static final class Builder {
        @Nullable
        private Object data;
        @Nullable
        private List<GraphQLError> errors;
        @Nullable
        private Map<Object, Object> extensions;

        private Builder() { }

        private Builder(GraphQLOutput output) {
            data = output.executionResult.getData();
            errors = output.executionResult.getErrors();
            extensions = output.executionResult.getExtensions();
        }

        private Builder(ExecutionResult executionResult) {
            requireNonNull(executionResult, "executionResult");
            data = executionResult.getData();
            errors = executionResult.getErrors();
            extensions = executionResult.getExtensions();
        }

        /**
         * Sets the data.
         */
        public Builder data(Object data) {
            this.data = data;
            return this;
        }

        /**
         * Sets the {@link GraphQLError}s.
         */
        public Builder errors(List<GraphQLError> errors) {
            this.errors = errors;
            return this;
        }

        /**
         * Sets the extensions.
         */
        public Builder extensions(Map<Object, Object> extensions) {
            this.extensions = extensions;
            return this;
        }

        /**
         * Returns new {@link GraphQLOutput} instance.
         */
        public GraphQLOutput build() {
            final ExecutionResult executionResult = new ExecutionResultImpl(data, errors, extensions);
            return new GraphQLOutput(executionResult);
        }
    }
}
