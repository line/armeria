/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import java.util.regex.Pattern;

final class GlobPathMapping extends AbstractPathMapping {

    private final Pattern pattern;
    private final String strVal;
    private final String glob;

    GlobPathMapping(String glob) {
        this.glob = glob;
        pattern = globToRegex(glob);
        strVal = "glob:" + glob;
    }

    @Override
    protected String doApply(String path) {
        return pattern.matcher(path).matches() ? path : null;
    }

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

    static Pattern globToRegex(String glob) {
        if (glob.charAt(0) != '/') {
            glob = "/**/" + glob;
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
                // Handle '/*/' specially.
                if (beforeAsterisk == '/' && c == '/') {
                    buf.append("[^/]+");
                } else {
                    buf.append("[^/]*");
                }
                break;
            case 2:
                // Handle '/**/' specially.
                if (beforeAsterisk == '/' && c == '/') {
                    buf.append("(?:.+/)?");
                    asterisks = 0;
                    beforeAsterisk = c;
                    continue;
                }

                buf.append(".*");
                break;
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
            if (beforeAsterisk == '/') {
                // '/*<END>'
                buf.append("[^/]+");
            } else {
                buf.append("[^/]*");
            }
            break;
        case 2:
            buf.append(".*");
            break;
        }

        return Pattern.compile(buf.append('$').toString());
    }
}
