/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.Nullable;

@SuppressWarnings({ "checkstyle:EqualsHashCode", "EqualsAndHashcode" })
final class DefaultQueryParams extends QueryParamsBase implements QueryParams {

    static final DefaultQueryParams EMPTY = new DefaultQueryParams();

    /**
     * Creates an empty parameters.
     */
    private DefaultQueryParams() {
        // Note that we do not specify a small size hint here, because a user may create a new builder
        // derived from an empty parameters and add many parameters. If we specified a small hint,
        // such a parameters would suffer from hash collisions.
        super(DEFAULT_SIZE_HINT);
    }

    /**
     * Creates a shallow copy of the specified {@link QueryParamsBase}.
     */
    DefaultQueryParams(QueryParamsBase params) {
        super(params, true);
    }

    @Override
    public QueryParamsBuilder toBuilder() {
        return new DefaultQueryParamsBuilder(this);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof QueryParams && super.equals(o);
    }
}
