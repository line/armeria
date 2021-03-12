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

import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.dataloader.DataLoaderRegistry;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.server.ServiceRequestContext;

import graphql.ExecutionInput;

/**
 * A input of a GraphQL query over HTTP.
 */
public final class GraphQLInput implements Request {

    private final String query;
    @Nullable
    private final Map<String, Object> variables;
    @Nullable
    private final String operationName;
    @Nullable
    private final Map<String, Object> extensions;
    @Nullable
    private final DataLoaderRegistry dataLoaderRegistry;
    private final ServiceRequestContext context;

    /**
     * Returns new {@link GraphQLOutput.Builder} instance.
     */
    public static Builder builder(String query, ServiceRequestContext context) {
        return new Builder(query, context);
    }

    private GraphQLInput(Builder builder) {
        query = requireNonNull(builder.query, "query");
        variables = builder.variables;
        operationName = builder.operationName;
        extensions = builder.extensions;
        dataLoaderRegistry = builder.dataLoaderRegistry;
        context = requireNonNull(builder.context, "context");
    }

    /**
     * Returns the query.
     */
    public String query() {
        return query;
    }

    /**
     * Returns the variables.
     */
    @Nullable
    public Map<String, Object> variables() {
        return variables;
    }

    /**
     * Returns the operation name.
     */
    @Nullable
    public String operationName() {
        return operationName;
    }

    /**
     * Returns the extensions.
     */
    @Nullable
    public Map<String, Object> extensions() {
        return extensions;
    }

    /**
     * Returns the {@link DataLoaderRegistry}.
     */
    @Nullable
    public DataLoaderRegistry dataLoaderRegistry() {
        return dataLoaderRegistry;
    }

    /**
     * Returns the {@link ServiceRequestContext}.
     */
    public ServiceRequestContext context() {
        return context;
    }

    /**
     * Converts to {@link ExecutionInput}.
     */
    public ExecutionInput toExecutionInput() {
        final ExecutionInput.Builder builder = ExecutionInput.newExecutionInput(query)
                                                             .context(context);
        if (variables != null) {
            builder.variables(variables);
        }
        if (operationName != null) {
            builder.operationName(operationName);
        }
        if (extensions != null) {
            builder.extensions(extensions);
        }
        if (extensions != null) {
            builder.extensions(extensions);
        }
        if (dataLoaderRegistry != null) {
            builder.dataLoaderRegistry(dataLoaderRegistry);
        }
        return builder.build();
    }

    /**
     * Transforms this instance through a {@link Builder} and
     * return a new instance with the modified values.
     */
    public GraphQLInput transform(Consumer<Builder> consumer) {
        final Builder builder = new Builder(this);
        consumer.accept(builder);
        return builder.build();
    }

    /**
     * Builds a new {@link GraphQLInput}.
     */
    public static final class Builder {
        private String query;
        @Nullable
        private Map<String, Object> variables;
        @Nullable
        private String operationName;
        @Nullable
        private Map<String, Object> extensions;
        @Nullable
        private DataLoaderRegistry dataLoaderRegistry;
        private ServiceRequestContext context;

        private Builder(String query, ServiceRequestContext context) {
            this.query = requireNonNull(query, "query");
            this.context = requireNonNull(context, "context");
        }

        private Builder(GraphQLInput input) {
            query = input.query;
            variables = input.variables;
            operationName = input.operationName;
            extensions = input.extensions;
            dataLoaderRegistry = input.dataLoaderRegistry;
            context = input.context;
        }

        /**
         * Sets the query.
         */
        public Builder query(String query) {
            this.query = requireNonNull(query, "query");
            return this;
        }

        /**
         * Sets the variables.
         */
        public Builder variables(Map<String, Object> variables) {
            this.variables = requireNonNull(variables, "variables");
            return this;
        }

        /**
         * Sets the operation name.
         */
        public Builder operationName(@Nullable String operationName) {
            this.operationName = operationName;
            return this;
        }

        /**
         * Sets the extensions.
         */
        public Builder extensions(Map<String, Object> extensions) {
            this.extensions = requireNonNull(extensions, "extensions");
            return this;
        }

        /**
         * Sets the {@link DataLoaderRegistry}.
         */
        public Builder dataLoaderRegistry(@Nullable DataLoaderRegistry dataLoaderRegistry) {
            this.dataLoaderRegistry = dataLoaderRegistry;
            return this;
        }

        /**
         * Sets the {@link ServiceRequestContext}.
         */
        public Builder context(ServiceRequestContext context) {
            this.context = context;
            return this;
        }

        /**
         * Returns new {@link GraphQLInput} instance.
         */
        public GraphQLInput build() {
            return new GraphQLInput(this);
        }
    }
}
