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
package com.linecorp.armeria.xds.internal;

import java.util.List;
import java.util.function.Predicate;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher.HeaderMatchSpecifierCase;
import io.envoyproxy.envoy.type.v3.Int64Range;

/**
 * Matches request headers against an xDS {@link HeaderMatcher}.
 */
public final class XdsHeaderMatcher {

    private static final Joiner COMMA_JOINER = Joiner.on(",");

    private final Predicate<HttpHeaders> matcher;
    private final HeaderMatcher headerMatcher;

    /**
     * Creates a single {@link XdsHeaderMatcher} from the given proto {@link HeaderMatcher}.
     */
    public static XdsHeaderMatcher of(HeaderMatcher headerMatcher) {
        return new XdsHeaderMatcher(headerMatcher);
    }

    /**
     * Creates a list of {@link XdsHeaderMatcher}s from the given proto {@link HeaderMatcher}s.
     */
    public static List<XdsHeaderMatcher> of(List<HeaderMatcher> headerMatchers) {
        if (headerMatchers.isEmpty()) {
            return ImmutableList.of();
        }
        final ImmutableList.Builder<XdsHeaderMatcher> builder = ImmutableList.builder();
        for (HeaderMatcher headerMatcher : headerMatchers) {
            builder.add(new XdsHeaderMatcher(headerMatcher));
        }
        return builder.build();
    }

    /**
     * Returns {@code true} if the given headers match all of the given matchers.
     */
    public static boolean matchAll(List<XdsHeaderMatcher> matchers, HttpHeaders headers) {
        for (XdsHeaderMatcher matcher : matchers) {
            if (!matcher.matches(headers)) {
                return false;
            }
        }
        return true;
    }

    private XdsHeaderMatcher(HeaderMatcher headerMatcher) {
        this.headerMatcher = headerMatcher;

        final HeaderMatchSpecifierCase matchCase = headerMatcher.getHeaderMatchSpecifierCase();
        switch (matchCase) {
            case EXACT_MATCH:
            case SAFE_REGEX_MATCH:
            case PREFIX_MATCH:
            case SUFFIX_MATCH:
            case CONTAINS_MATCH:
                throw new IllegalArgumentException("Using deprecated field: " + matchCase +
                                                   ". Use 'STRING_MATCH' instead.");
            case PRESENT_MATCH:
            case HEADERMATCHSPECIFIER_NOT_SET:
                final boolean presentMatch = headerMatcher.hasPresentMatch() ?
                                             headerMatcher.getPresentMatch() : true;
                matcher = headers -> {
                    if (headerMatcher.getTreatMissingHeaderAsEmpty()) {
                        return presentMatch;
                    }
                    return headers.contains(headerMatcher.getName()) == presentMatch;
                };
                break;
            case RANGE_MATCH:
                matcher = headers -> {
                    final Long value = headers.getLong(headerMatcher.getName());
                    if (value == null) {
                        return false;
                    }
                    final Int64Range rangeMatch = headerMatcher.getRangeMatch();
                    return value >= rangeMatch.getStart() && value < rangeMatch.getEnd();
                };
                break;
            case STRING_MATCH:
                final XdsStringMatcher stringMatcher =
                        new XdsStringMatcher(headerMatcher.getStringMatch());
                matcher = headers -> {
                    final List<String> allHeaders = headers.getAll(headerMatcher.getName());
                    if (allHeaders.isEmpty()) {
                        if (headerMatcher.getTreatMissingHeaderAsEmpty()) {
                            return stringMatcher.match("");
                        } else {
                            return false;
                        }
                    }
                    if (allHeaders.size() == 1) {
                        return stringMatcher.match(allHeaders.get(0));
                    }
                    final String joined = COMMA_JOINER.join(allHeaders);
                    return stringMatcher.match(joined);
                };
                break;
            default:
                throw new IllegalArgumentException("Unsupported header matchCase: " + matchCase + '.');
        }
    }

    /**
     * Returns {@code true} if the given headers match this matcher.
     */
    public boolean matches(@Nullable HttpHeaders headers) {
        if (headers == null) {
            headers = HttpHeaders.of();
        }
        return matcher.test(headers) != headerMatcher.getInvertMatch();
    }
}
