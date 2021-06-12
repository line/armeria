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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.internal.server.RouteUtil.GLOB;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.internal.common.util.StringUtil;

final class GlobPathMapping extends AbstractPathMapping {

    private final String glob;
    private final Pattern pattern;
    private final int numParams;
    private final Set<String> paramNames;
    private final String pathPattern;
    private final String strVal;
    private final List<String> paths;

    GlobPathMapping(String glob, int numGroupsToSkip) {
        final PatternAndParamCount patternAndParamCount = globToRegex(glob, numGroupsToSkip);

        this.glob = glob;
        pattern = patternAndParamCount.pattern;
        numParams = patternAndParamCount.numParams;

        final ImmutableSet.Builder<String> paramNames = ImmutableSet.builder();
        for (int i = 0; i < numParams; i++) {
            paramNames.add(StringUtil.toString(i));
        }
        this.paramNames = paramNames.build();

        strVal = GLOB + glob;

        // Make the glob pattern as an absolute form to distinguish 'glob:foo' from 'exact:/foo'
        // when generating logger and metric names.
        final String aGlob = glob.startsWith("/") ? glob : "/**/" + glob;
        pathPattern = aGlob;
        paths = ImmutableList.of(pattern.pattern(), aGlob);
    }

    @Nullable
    @Override
    RoutingResultBuilder doApply(RoutingContext routingCtx) {
        final Matcher m = pattern.matcher(routingCtx.path());
        if (!m.matches()) {
            return null;
        }

        final RoutingResultBuilder builder = RoutingResult.builderWithExpectedNumParams(numParams)
                                                          .path(routingCtx.path())
                                                          .query(routingCtx.query());
        for (int i = 1; i <= numParams; i++) {
            final String value = firstNonNull(m.group(i), "");
            builder.rawParam(StringUtil.toString(i - 1), value);
        }

        return builder;
    }

    @Override
    public Set<String> paramNames() {
        return paramNames;
    }

    @Override
    public String patternString() {
        return pathPattern;
    }

    @Override
    public RoutePathType pathType() {
        return RoutePathType.REGEX;
    }

    @Override
    public List<String> paths() {
        return paths;
    }

    @Override
    public int hashCode() {
        return glob.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof GlobPathMapping &&
               (this == obj || glob.equals(((GlobPathMapping) obj).glob));
    }

    @Override
    public String toString() {
        return strVal;
    }

    static PatternAndParamCount globToRegex(String glob, int numGroupsToSkip) {
        int numGroups = 0;
        if (glob.charAt(0) != '/') {
            glob = "/**/" + glob;
            numGroupsToSkip++; // Do not capture the prefix a user did not specify.
        }

        final int pathPatternLen = glob.length();
        final StringBuilder buf = new StringBuilder(pathPatternLen).append("^/");
        int asterisks = 0;
        char beforeAsterisk = '/';

        for (int i = 1; i < pathPatternLen; i++) { // Start from '1' to skip the first '/'.
            final char c = glob.charAt(i);
            if (c == '*') {
                asterisks++;
                if (asterisks > 2) {
                    throw new IllegalArgumentException(
                            "contains a path pattern with invalid wildcard characters: " + glob +
                            " (only * and ** are allowed)");
                }
                continue;
            }

            switch (asterisks) {
                case 1:
                    if (numGroupsToSkip <= 0) {
                        buf.append('(');
                        numGroups++;
                    }

                    // Handle '/*/' specially.
                    if (beforeAsterisk == '/' && c == '/') {
                        buf.append("[^/]+");
                    } else {
                        buf.append("[^/]*");
                    }

                    if (numGroupsToSkip <= 0) {
                        buf.append(')');
                    } else {
                        numGroupsToSkip--;
                    }
                    break;
                case 2:
                    // Handle '/**/' specially.
                    if (beforeAsterisk == '/' && c == '/') {
                        buf.append("(?:");

                        if (numGroupsToSkip <= 0) {
                            buf.append('(');
                            numGroups++;
                        }

                        buf.append(".+");

                        if (numGroupsToSkip <= 0) {
                            buf.append(')');
                        } else {
                            numGroupsToSkip--;
                        }

                        buf.append("/)?");
                        asterisks = 0;
                        continue;
                    } else {
                        if (numGroupsToSkip <= 0) {
                            buf.append('(');
                            numGroups++;
                        }

                        buf.append(".*");

                        if (numGroupsToSkip <= 0) {
                            buf.append(')');
                        } else {
                            numGroupsToSkip--;
                        }
                        break;
                    }
            }

            asterisks = 0;
            beforeAsterisk = c;

            switch (c) {
                case '\\':
                case '.':
                case '^':
                case '$':
                case '?':
                case '+':
                case '{':
                case '}':
                case '[':
                case ']':
                case '(':
                case ')':
                case '|':
                    buf.append('\\');
                    buf.append(c);
                    break;
                default:
                    buf.append(c);
            }
        }

        // Handle the case where the pattern ends with asterisk(s).
        switch (asterisks) {
            case 1:
                if (numGroupsToSkip <= 0) {
                    buf.append('(');
                    numGroups++;
                }

                if (beforeAsterisk == '/') {
                    // '/*<END>'
                    buf.append("[^/]+");
                } else {
                    buf.append("[^/]*");
                }

                if (numGroupsToSkip <= 0) {
                    buf.append(')');
                }
                break;
            case 2:
                if (numGroupsToSkip <= 0) {
                    buf.append('(');
                    numGroups++;
                }

                buf.append(".*");

                if (numGroupsToSkip <= 0) {
                    buf.append(')');
                }
                break;
        }

        return new PatternAndParamCount(Pattern.compile(buf.append('$').toString()), numGroups);
    }

    private static final class PatternAndParamCount {
        final Pattern pattern;
        final int numParams;

        PatternAndParamCount(Pattern pattern, int numParams) {
            this.pattern = pattern;
            this.numParams = numParams;
        }
    }
}
