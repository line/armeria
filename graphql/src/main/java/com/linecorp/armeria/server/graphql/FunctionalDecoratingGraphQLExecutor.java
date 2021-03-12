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

import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A decorating {@link GraphQLExecutor} which implements its {@link #serve(ServiceRequestContext, GraphQLInput)}
 * method using a given function.
 *
 * @see GraphQLExecutor#decorate(DecoratingGraphQLExecutorFunction)
 */
final class FunctionalDecoratingGraphQLExecutor extends SimpleDecoratingGraphQLExecutor {

    private final DecoratingGraphQLExecutorFunction function;

    /**
     * Creates a new instance with the specified function.
     */
    FunctionalDecoratingGraphQLExecutor(GraphQLExecutor delegate,
                                        DecoratingGraphQLExecutorFunction function) {
        super(delegate);
        this.function = requireNonNull(function, "function");
    }

    @Override
    public GraphQLOutput serve(ServiceRequestContext ctx, GraphQLInput input) throws Exception {
        return function.serve((GraphQLExecutor) unwrap(), ctx, input);
    }

    @Override
    public String toString() {
        return FunctionalDecoratingGraphQLExecutor.class.getSimpleName() + '(' + unwrap() + ", " + function +
               ')';
    }
}
