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

import java.util.Objects;

import com.google.common.base.MoreObjects;

final class DefaultHttpJsonTranscodingOptions implements HttpJsonTranscodingOptions {

    static final HttpJsonTranscodingOptions DEFAULT = HttpJsonTranscodingOptions.builder().build();

    private final boolean useCamelCaseQueryParams;
    private final boolean useProtoFieldNameQueryParams;

    DefaultHttpJsonTranscodingOptions(boolean useCamelCaseQueryParams, boolean useProtoFieldNameQueryParams) {
        this.useCamelCaseQueryParams = useCamelCaseQueryParams;
        this.useProtoFieldNameQueryParams = useProtoFieldNameQueryParams;
    }

    @Override
    public boolean useCamelCaseQueryParams() {
        return useCamelCaseQueryParams;
    }

    @Override
    public boolean useProtoFieldNameQueryParams() {
        return useProtoFieldNameQueryParams;
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
        return useProtoFieldNameQueryParams == that.useProtoFieldNameQueryParams() &&
               useCamelCaseQueryParams == that.useCamelCaseQueryParams();
    }

    @Override
    public int hashCode() {
        return Objects.hash(useProtoFieldNameQueryParams, useCamelCaseQueryParams);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("useCamelCaseQueryParams", useCamelCaseQueryParams)
                          .add("useProtoFieldNameQueryParams", useProtoFieldNameQueryParams)
                          .toString();
    }
}
