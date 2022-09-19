/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.google.common.base.MoreObjects;

final class DefaultHttpJsonTranscodingOptions implements HttpJsonTranscodingOptions {

    static final HttpJsonTranscodingOptions DEFAULT = HttpJsonTranscodingOptions.builder().build();

    private final EnumSet<HttpJsonTranscodingQueryParamNaming> queryParamNamings;
    private final UnframedGrpcErrorHandler errorHandler;

    DefaultHttpJsonTranscodingOptions(EnumSet<HttpJsonTranscodingQueryParamNaming> queryParamNamings,
                                      UnframedGrpcErrorHandler errorHandler) {
        this.queryParamNamings = queryParamNamings;
        this.errorHandler = errorHandler;
    }

    @Override
    public Set<HttpJsonTranscodingQueryParamNaming> queryParamNamings() {
        return queryParamNamings;
    }

    @Override
    public UnframedGrpcErrorHandler errorHandler() {
        return errorHandler;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpJsonTranscodingOptions)) {
            return false;
        }
        final HttpJsonTranscodingOptions that = (HttpJsonTranscodingOptions) o;
        return queryParamNamings.equals(that.queryParamNamings()) && errorHandler.equals(that.errorHandler());
    }

    @Override
    public int hashCode() {
        return Objects.hash(queryParamNamings, errorHandler);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("queryParamNamings", queryParamNamings)
                          .add("errorHandler", errorHandler)
                          .toString();
    }
}
