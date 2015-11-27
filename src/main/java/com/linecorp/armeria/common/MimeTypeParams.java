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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MimeTypeParams {

    private static final Pattern PARAM_PATTERN = Pattern.compile(
            "\\s*;\\s*([^=\\s]+)\\s*=\\s*(?:([^\"][^;]*)|\"((?:[^\\\\\"]|\\\\.)*)\")");

    static String find(String params, String name) {
        assert name != null;
        if (params == null) {
            return null;
        }

        final Matcher m = PARAM_PATTERN.matcher(params);
        while (m.find()) {
            final String matchedName = m.group(1);
            final String unquotedValue = m.group(2);
            final String quotedValue = m.group(3);
            if (name.equals(matchedName)) {
                return unquotedValue != null ? unquotedValue : unquote(quotedValue);
            }
        }

        return null;
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
