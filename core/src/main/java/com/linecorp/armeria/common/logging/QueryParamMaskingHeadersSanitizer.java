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

package com.linecorp.armeria.common.logging;

import static com.linecorp.armeria.internal.common.PercentDecoder.decodeComponent;
import static com.linecorp.armeria.internal.common.PercentEncoder.encodeComponent;

import java.util.Set;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

final class QueryParamMaskingHeadersSanitizer<T> implements HeadersSanitizer<T> {

    private final HeadersSanitizer<T> delegate;
    private final Set<String> queryParamsToMask;
    private final QueryParamMaskingFunction maskingFunction;

    QueryParamMaskingHeadersSanitizer(HeadersSanitizer<T> delegate,
                                      Set<String> queryParamsToMask,
                                      QueryParamMaskingFunction maskingFunction) {
        this.delegate = delegate;
        this.queryParamsToMask = queryParamsToMask;
        this.maskingFunction = maskingFunction;
    }

    @Nullable
    @Override
    public T sanitize(RequestContext ctx, HttpHeaders headers) {
        final String path = headers.get(HttpHeaderNames.PATH);
        if (path == null) {
            return delegate.sanitize(ctx, headers);
        }

        final int queryStart = path.indexOf('?') + 1;
        if (queryStart == 0 || queryStart == path.length()) {
            return delegate.sanitize(ctx, headers);
        }

        final String maskedPath = maskQueryParams(path, queryStart);
        if (maskedPath == null) {
            return delegate.sanitize(ctx, headers);
        }
        return delegate.sanitize(
                ctx, headers.withMutations(builder -> builder.set(HttpHeaderNames.PATH, maskedPath)));
    }

    @Nullable
    private String maskQueryParams(String path, int queryStart) {
        final int fragmentStart = path.indexOf('#', queryStart);
        final int queryEnd = fragmentStart >= 0 ? fragmentStart : path.length();
        if (queryStart == queryEnd) {
            return null;
        }

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder maskedQuery = tempThreadLocals.stringBuilder();
            boolean masked = false;
            boolean retained = false;
            int componentStart = queryStart;
            for (;;) {
                int componentEnd = path.indexOf('&', componentStart);
                if (componentEnd < 0 || componentEnd > queryEnd) {
                    componentEnd = queryEnd;
                }

                boolean matched = false;
                if (componentStart != componentEnd) {
                    final int equalsPos = firstEqualsOrEnd(path, componentStart, componentEnd);
                    final String name =
                            decodeComponent(tempThreadLocals, path, componentStart, equalsPos);
                    if (queryParamsToMask.contains(name)) {
                        matched = true;
                        masked = true;
                        final int valueStart = equalsPos < componentEnd ? equalsPos + 1 : componentEnd;
                        final String value =
                                decodeComponent(tempThreadLocals, path, valueStart, componentEnd);
                        final String maskedValue = maskingFunction.mask(name, value);
                        if (maskedValue != null) {
                            if (retained) {
                                maskedQuery.append('&');
                            }
                            maskedQuery.append(path, componentStart, equalsPos).append('=');
                            encodeComponent(maskedQuery, maskedValue);
                            retained = true;
                        }
                    }
                }

                if (!matched) {
                    if (retained) {
                        maskedQuery.append('&');
                    }
                    maskedQuery.append(path, componentStart, componentEnd);
                    retained = true;
                }

                if (componentEnd == queryEnd) {
                    break;
                }
                componentStart = componentEnd + 1;
            }

            if (!masked) {
                return null;
            }

            final int prefixEnd = retained ? queryStart : queryStart - 1;
            final StringBuilder maskedPath = new StringBuilder(path.length() + 16);
            maskedPath.append(path, 0, prefixEnd);
            if (retained) {
                maskedPath.append(maskedQuery);
            }
            return maskedPath.append(path, queryEnd, path.length()).toString();
        }
    }

    private static int firstEqualsOrEnd(String path, int componentStart, int componentEnd) {
        for (int i = componentStart; i < componentEnd; i++) {
            if (path.charAt(i) == '=') {
                return i;
            }
        }
        return componentEnd;
    }
}
