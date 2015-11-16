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

import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

final class RegexPathMapping extends AbstractPathMapping {

    private final Pattern regex;
    private final String strVal;

    RegexPathMapping(Pattern regex) {
        this.regex = requireNonNull(regex, "regex");
        strVal = "regex: " + regex.pattern();
    }

    @Override
    protected String doApply(String path) {
        return regex.matcher(path).find() ? path : null;
    }

    @Override
    public int hashCode() {
        return strVal.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RegexPathMapping &&
               (this == obj || regex.pattern().equals(((RegexPathMapping) obj).regex.pattern()));
    }

    @Override
    public String toString() {
        return strVal;
    }
}
