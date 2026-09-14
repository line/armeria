/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;

final class RequestHeadersMutationChain implements RequestHeadersMutator {

    static final RequestHeadersMutationChain EMPTY = new RequestHeadersMutationChain(ImmutableList.of(), false);

    private final List<RequestHeadersMutation> mutations;
    private final boolean mostSpecificWins;

    private RequestHeadersMutationChain(List<RequestHeadersMutation> mutations, boolean mostSpecificWins) {
        this.mutations = mutations;
        this.mostSpecificWins = mostSpecificWins;
    }

    static RequestHeadersMutationChain of(RequestHeadersMutation routeConfig, RequestHeadersMutation vhost,
                                          RequestHeadersMutation route, boolean mostSpecificWins) {
        if (routeConfig.isEmpty() && vhost.isEmpty() && route.isEmpty()) {
            return EMPTY;
        }
        final ImmutableList<RequestHeadersMutation> mutations;
        if (mostSpecificWins) {
            mutations = ImmutableList.of(routeConfig, vhost, route);
        } else {
            mutations = ImmutableList.of(route, vhost, routeConfig);
        }
        return new RequestHeadersMutationChain(mutations, mostSpecificWins);
    }

    RequestHeadersMutationChain append(RequestHeadersMutation mutation) {
        if (mutation.isEmpty() && mutations.isEmpty()) {
            return EMPTY;
        }
        final ImmutableList.Builder<RequestHeadersMutation> builder = ImmutableList.builder();
        if (mostSpecificWins) {
            builder.addAll(mutations);
            builder.add(mutation);
        } else {
            builder.add(mutation);
            builder.addAll(mutations);
        }
        return new RequestHeadersMutationChain(builder.build(), mostSpecificWins);
    }

    private boolean isEmpty() {
        for (RequestHeadersMutation mutation : mutations) {
            if (!mutation.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public RequestHeaders apply(RequestHeaders original) {
        if (isEmpty()) {
            return original;
        }
        final RequestHeadersBuilder builder = original.toBuilder();
        for (RequestHeadersMutation mutation : mutations) {
            mutation.applyTo(builder);
        }
        return builder.build();
    }
}
