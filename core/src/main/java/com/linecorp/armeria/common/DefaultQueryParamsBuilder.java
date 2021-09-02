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

final class DefaultQueryParamsBuilder extends StringMultimapBuilder<
        /* IN_NAME */ String, /* NAME */ String,
        /* CONTAINER */ QueryParamsBase, /* SELF */ QueryParamsBuilder>
        implements QueryParamsBuilder {

    DefaultQueryParamsBuilder() {}

    DefaultQueryParamsBuilder(QueryParamsBase parent) {
        super(parent);
        assert parent instanceof QueryParams;
    }

    @Override
    QueryParamsBase newSetters(int sizeHint) {
        return new QueryParamsBase(sizeHint);
    }

    @Override
    QueryParamsBase newSetters(QueryParamsBase parent, boolean shallowCopy) {
        return new QueryParamsBase(parent, shallowCopy);
    }

    @Override
    public QueryParams build() {
        @Nullable
        final QueryParamsBase delegate = delegate();
        if (delegate != null) {
            if (delegate.isEmpty()) {
                return DefaultQueryParams.EMPTY;
            } else {
                return new DefaultQueryParams(promoteDelegate());
            }
        }

        @Nullable
        final QueryParamsBase parent = parent();
        if (parent != null) {
            if (parent instanceof QueryParams) {
                return (QueryParams) parent;
            }
            return updateParent(new DefaultQueryParams(parent));
        }

        return DefaultQueryParams.EMPTY;
    }
}
