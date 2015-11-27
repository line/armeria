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

package com.linecorp.armeria.common;

final class MimeTypeParams {

    static String find(String params, String name) {
        if (params == null) {
            return null;
        }

        final int length = params.length();
        if (length - 2 < name.length()) {
            return null;
        }

        int i = 0;
        for (;;) {
            // Skip whitespace characters.
            i = skipWhitespace(params, i);
            if (i >= length) {
                return null;
            }

            // Expect a semicolon.
            if (params.charAt(i) != ';') {
                return null;
            }

            // Skip whitespace characters.
            i = skipWhitespace(params, i + 1);
            if (i >= length) {
                return null;
            }

            // Find the last index of the name part.
            int lastIndex;
            for (lastIndex = i; i < length && isTokenChar(params.charAt(i)); i ++) {
                continue;
            }

            // Get the name part.
            final boolean found = params.substring(lastIndex, i).equals(name);

            // Skip whitespace characters.
            i = skipWhitespace(params, i);
            if (i >= length) {
                return null;
            }

            // Expect an equal sign.
            if (params.charAt(i) != '=') {
                return null;
            }

            // Skip whitespace characters.
            i = skipWhitespace(params, i + 1);
            if(i >= length) {
                return null;
            }

            // Parse a value (unquoted or quoted)
            char c = params.charAt(i);
            if (c != '"') { // Parse a unquoted value.
                if (!isTokenChar(c)) {
                    // Not a token character
                    return null;
                }

                for (lastIndex = i; i < length && isTokenChar(params.charAt(i)); i++) {
                    continue;
                }

                if (found) {
                    return params.substring(lastIndex, i);
                }
            } else { // Parse a quoted value.
                // Skip the opening quote.
                i ++;
                if (i >= length) {
                    // Met the end of string right after the opening quote.
                    return null;
                }

                // Find the closing quote.
                for (lastIndex = i; i < length; i++) {
                    c = params.charAt(i);
                    if (c == '"') {
                        break;
                    }

                    if (c == '\\') {
                        i++;
                    }
                }

                if (c != '"') {
                    // Couldn't find the closing quote.
                    return null;
                }

                if (found) {
                    return unquote(params.substring(lastIndex, i));
                }

                // Skip the closing quote.
                i++;
            }
        }
    }

    private static int skipWhitespace(String str, int i) {
        final int length = str.length();
        for (; i < length && Character.isWhitespace(str.charAt(i)); i ++) {
            continue;
        }

        return i;
    }

    private static boolean isTokenChar(char c) {
        return c > 32 && c < 127 && "()<>@,;:/[]?=\\\"".indexOf(c) < 0;
    }

    private static String unquote(String value) {
        final int length = value.length();
        final StringBuilder buf = new StringBuilder(length);

        boolean escaped = false;
        for(int i = 0; i < length; i++) {
            final char c = value.charAt(i);
            if (escaped) {
                buf.append(c);
                escaped = false;
            } else if (c != '\\') {
                buf.append(c);
            } else {
                escaped = true;
            }
        }

        return buf.toString();
    }

    private MimeTypeParams() {}
}
