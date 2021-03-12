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

import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.server.Service;

/**
 * An GraphQL in/output {@link Service}.
 */
@FunctionalInterface
public interface GraphQLExecutor extends Service<GraphQLInput, GraphQLOutput> {

    /**
     * Creates a new {@link Service} that decorates this {@link GraphQLExecutor} with the specified
     * {@code decorator}.
     */
    default <R extends Service<R_I, R_O>, R_I extends Request, R_O extends Response>
    R decorate(Function<? super GraphQLExecutor, R> decorator) {
        final R newService = decorator.apply(this);

        if (newService == null) {
            throw new NullPointerException("decorator.apply() returned null: " + decorator);
        }

        return newService;
    }

    /**
     * Creates a new {@link GraphQLExecutor} that decorates this {@link GraphQLExecutor} with the specified
     * {@link DecoratingGraphQLExecutorFunction}.
     */
    default GraphQLExecutor decorate(DecoratingGraphQLExecutorFunction function) {
        return new FunctionalDecoratingGraphQLExecutor(this, function);
    }
}
