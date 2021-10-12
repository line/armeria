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

package com.linecorp.armeria.internal.server.graphql.protocol;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.graphql.protocol.AbstractGraphqlService;

import java.util.List;

/**
 * Utility for handling the GraphQL protocol.
 */
public final class GraphqlUtil {

    /**
     * Returns the negotiated {@link MediaType}. {@link MediaType#JSON} and {@link MediaType#GRAPHQL_JSON}
     * are commonly used for the Content-Type of a GraphQL response.
     * If {@link HttpHeaderNames#ACCEPT} is not specified, {@link MediaType#GRAPHQL_JSON} is used by default.
     *
     * <p>Note that the negotiated {@link MediaType} could not be used by the implementation of
     * {@link AbstractGraphqlService} which may choose to respond in one of several ways
     * specified the
     * <a href="https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#body">
     * specification</a>.
     */
    @Nullable
    public static MediaType produceType(RequestHeaders headers) {
        MediaType contentType = headers.contentType();
        if (HttpMethod.POST.equals(headers.method()) &&
                contentType != null && contentType.is(MediaType.GRAPHQL)) {
            return MediaType.GRAPHQL_JSON;
        }

        final List<MediaType> acceptTypes = headers.accept();
        if (acceptTypes.isEmpty()) {
            // If there is no Accept header in the request, the response MUST include
            // a Content-Type: application/graphql+json header
            return MediaType.GRAPHQL_JSON;
        }

        for (MediaType accept : acceptTypes) {
            if (accept.is(MediaType.GRAPHQL_JSON) || accept.is(MediaType.JSON)) {
                return accept;
            }
        }

        // Not acceptable
        return null;
    }

    private GraphqlUtil() {}
}
