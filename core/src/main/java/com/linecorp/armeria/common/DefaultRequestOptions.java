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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Objects;

import com.google.common.base.MoreObjects;

final class DefaultRequestOptions implements RequestOptions {

    static final DefaultRequestOptions EMPTY = new DefaultRequestOptions(-1);

    private final long responseTimeoutMillis;

    DefaultRequestOptions(long responseTimeoutMillis) {
        checkArgument(responseTimeoutMillis >= -1, "responseTimeoutMillis: %s (expected: >= -1)");
        this.responseTimeoutMillis = responseTimeoutMillis;
    }

    @Override
    public long responseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof DefaultRequestOptions)) {
            return false;
        }

        final DefaultRequestOptions that = (DefaultRequestOptions) o;
        return responseTimeoutMillis == that.responseTimeoutMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(responseTimeoutMillis);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("responseTimeoutMillis", responseTimeoutMillis)
                          .toString();
    }
}
