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

import javax.annotation.Nullable;

import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * An {@link GraphQLExecutor} that decorates another {@link GraphQLExecutor}.
 *
 * @see GraphQLExecutor#decorate(DecoratingGraphQLExecutorFunction)
 */
public abstract class SimpleDecoratingGraphQLExecutor
        extends SimpleDecoratingService<GraphQLInput, GraphQLOutput>
        implements GraphQLExecutor {

    /**
     * Creates a new instance that decorates the specified {@link GraphQLExecutor}.
     */
    protected SimpleDecoratingGraphQLExecutor(GraphQLExecutor delegate) {
        super(delegate);
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean shouldCachePath(String path, @Nullable String query, Route route) {
        throw new UnsupportedOperationException();
    }
}
