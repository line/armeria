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

import java.util.Collections;
import java.util.List;

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;

import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;

final class RequestHeadersMutation implements RequestHeadersMutator {

    static final RequestHeadersMutation EMPTY =
            new RequestHeadersMutation(Collections.emptyList(), Collections.emptyList());

    private final List<HeaderValueOption> headersToAdd;
    private final List<String> headersToRemove;

    private RequestHeadersMutation(List<HeaderValueOption> headersToAdd, List<String> headersToRemove) {
        this.headersToAdd = headersToAdd;
        this.headersToRemove = headersToRemove;
    }

    static RequestHeadersMutation of(List<HeaderValueOption> toAdd, List<String> toRemove) {
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            return EMPTY;
        }
        return new RequestHeadersMutation(toAdd, toRemove);
    }

    boolean isEmpty() {
        return headersToAdd.isEmpty() && headersToRemove.isEmpty();
    }

    @Override
    public RequestHeaders apply(RequestHeaders original) {
        if (isEmpty()) {
            return original;
        }
        final RequestHeadersBuilder builder = original.toBuilder();
        applyTo(builder);
        return builder.build();
    }

    void applyTo(RequestHeadersBuilder builder) {
        for (String name : headersToRemove) {
            builder.remove(name);
        }
        for (HeaderValueOption option : headersToAdd) {
            final String key = option.getHeader().getKey();
            final String value = option.getHeader().getValue();

            if (value.isEmpty() && !option.getKeepEmptyValue()) {
                continue;
            }

            switch (option.getAppendAction()) {
                case APPEND_IF_EXISTS_OR_ADD:
                    builder.add(key, value);
                    break;
                case ADD_IF_ABSENT:
                    if (!builder.contains(key)) {
                        builder.add(key, value);
                    }
                    break;
                case OVERWRITE_IF_EXISTS_OR_ADD:
                    builder.set(key, value);
                    break;
                case OVERWRITE_IF_EXISTS:
                    if (builder.contains(key)) {
                        builder.set(key, value);
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
