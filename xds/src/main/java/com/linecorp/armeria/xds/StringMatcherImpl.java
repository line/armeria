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

import java.util.Objects;
import java.util.function.Predicate;

import com.google.common.base.Ascii;
import com.google.re2j.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher.MatchPatternCase;

class StringMatcherImpl {

    private final boolean ignoreCase;
    private final MatchPatternCase patternCase;
    private final Predicate<String> predicate;
    @Nullable
    private final String exact;
    @Nullable
    private final String patternValue;

    StringMatcherImpl(StringMatcher stringMatcher) {
        ignoreCase = stringMatcher.getIgnoreCase();
        patternCase = stringMatcher.getMatchPatternCase();
        switch (patternCase) {
            case EXACT:
                final String exactValue;
                if (ignoreCase) {
                    exactValue = Ascii.toLowerCase(stringMatcher.getExact());
                } else {
                    exactValue = stringMatcher.getExact();
                }
                exact = exactValue;
                patternValue = exactValue;
                predicate = str -> str.equals(exactValue);
                break;
            case PREFIX:
                final String prefix;
                if (ignoreCase) {
                    prefix = Ascii.toLowerCase(stringMatcher.getPrefix());
                } else {
                    prefix = stringMatcher.getPrefix();
                }
                exact = null;
                patternValue = prefix;
                predicate = str -> str.startsWith(prefix);
                break;
            case SUFFIX:
                final String suffix;
                if (ignoreCase) {
                    suffix = Ascii.toLowerCase(stringMatcher.getSuffix());
                } else {
                    suffix = stringMatcher.getSuffix();
                }
                exact = null;
                patternValue = suffix;
                predicate = str -> str.endsWith(suffix);
                break;
            case SAFE_REGEX:
                final RegexMatcher safeRegex = stringMatcher.getSafeRegex();
                final Pattern pattern = Pattern.compile(safeRegex.getRegex());
                exact = null;
                patternValue = safeRegex.getRegex();
                predicate = str -> pattern.matcher(str).matches();
                break;
            case CONTAINS:
                final String contains;
                if (ignoreCase) {
                    contains = Ascii.toLowerCase(stringMatcher.getContains());
                } else {
                    contains = stringMatcher.getContains();
                }
                exact = null;
                patternValue = contains;
                predicate = str -> str.contains(contains);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported string matcher patternCase: " + patternCase);
        }
    }

    boolean match(@Nullable String input) {
        if (input == null) {
            return false;
        }
        if (ignoreCase && patternCase != MatchPatternCase.SAFE_REGEX) {
            input = Ascii.toLowerCase(input);
        }
        return predicate.test(input);
    }

    boolean ignoreCase() {
        return ignoreCase;
    }

    boolean isExact() {
        return patternCase == MatchPatternCase.EXACT;
    }

    @Nullable
    String exact() {
        return exact;
    }

    @Override
    public String toString() {
        return "StringMatcherImpl{" +
               "patternCase=" + patternCase +
               ", ignoreCase=" + ignoreCase +
               ", patternValue=" + patternValue +
               '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(patternCase, ignoreCase, patternValue);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StringMatcherImpl)) {
            return false;
        }
        final StringMatcherImpl that = (StringMatcherImpl) obj;
        return patternCase == that.patternCase &&
               ignoreCase == that.ignoreCase &&
               Objects.equals(patternValue, that.patternValue);
    }
}
