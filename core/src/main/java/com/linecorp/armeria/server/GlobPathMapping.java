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

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

final class GlobPathMapping extends AbstractPathMapping {

    static final String PREFIX = "glob:";
    static final int PREFIX_LEN = PREFIX.length();

    private static final String[] INT_TO_STRING;

    static {
        INT_TO_STRING = new String[64];
        for (int i = 0; i < INT_TO_STRING.length; i++) {
            INT_TO_STRING[i] = String.valueOf(i);
        }
    }

    private static String int2str(int value) {
        if (value < INT_TO_STRING.length) {
            return INT_TO_STRING[value];
        } else {
            return Integer.toString(value);
        }
    }

    private final String glob;
    private final Pattern pattern;
    private final int numParams;
    private final Set<String> paramNames;
    private final String loggerName;
    private final String meterTag;
    private final String strVal;

    GlobPathMapping(String glob) {
        final PatternAndParamCount patternAndParamCount = globToRegex(glob);

        this.glob = glob;
        pattern = patternAndParamCount.pattern;
        numParams = patternAndParamCount.numParams;

        final ImmutableSet.Builder<String> paramNames = ImmutableSet.builder();
        for (int i = 0; i < numParams; i++) {
            paramNames.add(int2str(i));
        }
        this.paramNames = paramNames.build();

        strVal = PREFIX + glob;

        // Make the glob pattern as an absolute form to distinguish 'glob:foo' from 'exact:/foo'
        // when generating logger and metric names.
        final String aGlob = glob.startsWith("/") ? glob : "/**/" + glob;
        loggerName = loggerName(aGlob);
        meterTag = PREFIX + aGlob;
    }

    @Override
    protected PathMappingResult doApply(PathMappingContext mappingCtx) {
        final Matcher m = pattern.matcher(mappingCtx.path());
        if (!m.matches()) {
            return PathMappingResult.empty();
        }

        if (numParams == 0) {
            return PathMappingResult.of(mappingCtx.path(), mappingCtx.query());
        }

        final ImmutableMap.Builder<String, String> params = ImmutableMap.builder();
        for (int i = 1; i <= numParams; i++) {
            final String value = firstNonNull(m.group(i), "");
            params.put(int2str(i - 1), value);
        }

        return PathMappingResult.of(mappingCtx.path(), mappingCtx.query(), params.build());
    }

    @Override
    public Set<String> paramNames() {
        return paramNames;
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    @Override
    public String meterTag() {
        return meterTag;
    }

    @VisibleForTesting
    Pattern asRegex() {
        return pattern;
    }

    @Override
    public int hashCode() {
        return strVal.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof GlobPathMapping &&
               (this == obj || glob.equals(((GlobPathMapping) obj).glob));
    }

    @Override
    public String toString() {
        return strVal;
    }

    static PatternAndParamCount globToRegex(String glob) {
        boolean createGroup;
        int numGroups = 0;
        if (glob.charAt(0) != '/') {
            glob = "/**/" + glob;
            createGroup = false; // Do not capture the prefix a user did not specify.
        } else {
            createGroup = true;
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
                if (createGroup) {
                    buf.append('(');
                    numGroups++;
                }

                // Handle '/*/' specially.
                if (beforeAsterisk == '/' && c == '/') {
                    buf.append("[^/]+");
                } else {
                    buf.append("[^/]*");
                }

                if (createGroup) {
                    buf.append(')');
                } else {
                    createGroup = true;
                }
                break;
            case 2:
                // Handle '/**/' specially.
                if (beforeAsterisk == '/' && c == '/') {
                    buf.append("(?:");

                    if (createGroup) {
                        buf.append('(');
                        numGroups++;
                    }

                    buf.append(".+");

                    if (createGroup) {
                        buf.append(')');
                    } else {
                        createGroup = false;
                    }

                    buf.append("/)?");
                    asterisks = 0;
                    continue;
                } else {
                    if (createGroup) {
                        buf.append('(');
                        numGroups++;
                    }

                    buf.append(".*");

                    if (createGroup) {
                        buf.append(')');
                    } else {
                        createGroup = false;
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
            if (createGroup) {
                buf.append('(');
                numGroups++;
            }

            if (beforeAsterisk == '/') {
                // '/*<END>'
                buf.append("[^/]+");
            } else {
                buf.append("[^/]*");
            }

            if (createGroup) {
                buf.append(')');
            }
            break;
        case 2:
            if (createGroup) {
                buf.append('(');
                numGroups++;
            }

            buf.append(".*");

            if (createGroup) {
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
