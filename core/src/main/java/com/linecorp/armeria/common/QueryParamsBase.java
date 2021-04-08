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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linecorp.armeria.common;

import javax.annotation.Nullable;

/**
 * The base container implementation of {@link QueryParams} and {@link QueryParamsBuilder}.
 */
@SuppressWarnings({ "checkstyle:EqualsHashCode", "EqualsAndHashcode" })
class QueryParamsBase
        extends StringMultimap</* IN_NAME */ String, /* NAME */ String>
        implements QueryParamGetters {

    QueryParamsBase(int sizeHint) {
        super(sizeHint);
    }

    /**
     * Creates a shallow or deep copy of the specified {@link QueryParamsBase}.
     */
    QueryParamsBase(QueryParamsBase parent, boolean shallowCopy) {
        super(parent, shallowCopy);
    }

    @Override
    final int hashName(String s) {
        return s.hashCode();
    }

    @Override
    final boolean nameEquals(String a, String b) {
        // Keys in URL parameters are case-sensitive - https://datatracker.ietf.org/doc/html/rfc3986#page-39
        return a.equals(b);
    }

    @Override
    final boolean isFirstGroup(String s) {
        return true;
    }

    @Override
    final String normalizeName(String s) {
        return s;
    }

    @Override
    final void validateValue(String value) {}

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof QueryParamGetters)) {
            return false;
        }

        return super.equals(o);
    }
}
