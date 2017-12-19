/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

final class RegexPathMapping extends AbstractPathMapping {

    static final String PREFIX = "regex:";
    static final int PREFIX_LEN = PREFIX.length();

    private static final Pattern NAMED_GROUP_PATTERN = Pattern.compile("\\(\\?<([^>]+)>");

    private final Pattern regex;
    private final Set<String> paramNames;
    private final String loggerName;
    private final String meterTag;

    RegexPathMapping(Pattern regex) {
        this.regex = requireNonNull(regex, "regex");
        paramNames = findParamNames(regex);
        loggerName = toLoggerName(regex);
        meterTag = PREFIX + regex.pattern();
    }

    private static Set<String> findParamNames(Pattern regex) {
        final Matcher matcher = NAMED_GROUP_PATTERN.matcher(regex.pattern());
        final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        int pos = 0;
        while (matcher.find(pos)) {
            builder.add(matcher.group(1));
            pos = matcher.end();
        }
        return builder.build();
    }

    @Override
    protected PathMappingResult doApply(PathMappingContext mappingCtx) {
        final Matcher matcher = regex.matcher(mappingCtx.path());
        if (!matcher.find()) {
            return PathMappingResult.empty();
        }

        ImmutableMap.Builder<String, String> builder = null;
        for (String name : paramNames) {
            final String value = matcher.group(name);
            if (value == null) {
                continue;
            }

            if (builder == null) {
                builder = ImmutableMap.builder();
            }
            builder.put(name, value);
        }

        return PathMappingResult.of(mappingCtx.path(), mappingCtx.query(),
                                    builder != null ? builder.build() : ImmutableMap.of());
    }

    @Override
    public Set<String> paramNames() {
        return paramNames;
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    private static String toLoggerName(Pattern regex) {
        final String regexStr = regex.pattern();
        final String prefix = "regex.";
        final StringBuilder buf = new StringBuilder(prefix.length() + regexStr.length());
        buf.append(prefix);
        for (int i = 0; i < regexStr.length(); i++) {
            final char ch = regexStr.charAt(i);
            if (Character.isJavaIdentifierPart(ch)) {
                buf.append(ch);
            } else {
                buf.append('_');
            }
        }
        return buf.toString();
    }

    @Override
    public String meterTag() {
        return meterTag;
    }

    @VisibleForTesting
    Pattern asRegex() {
        return regex;
    }

    @Override
    public int hashCode() {
        return meterTag.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RegexPathMapping &&
               (this == obj || regex.pattern().equals(((RegexPathMapping) obj).regex.pattern()));
    }

    @Override
    public String toString() {
        return meterTag;
    }
}
