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

final class PrefixPathMapping extends AbstractPathMapping {

    private final String prefix;
    private final boolean stripPrefix;
    private final String strVal;

    PrefixPathMapping(String prefix, boolean stripPrefix) {
        prefix = ExactPathMapping.ensureAbsolutePath(prefix, "prefix");
        if (!prefix.endsWith("/")) {
            prefix += '/';
        }

        this.prefix = prefix;
        this.stripPrefix = stripPrefix;
        strVal = "prefix: " + prefix;
    }

    @Override
    protected String doApply(String path) {
        if (!path.startsWith(prefix)) {
            return null;
        }

        return stripPrefix ? path.substring(prefix.length() - 1) : path;
    }

    @Override
    public int hashCode() {
        return strVal.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PrefixPathMapping &&
               (this == obj || prefix.equals(((PrefixPathMapping) obj).prefix));

    }

    @Override
    public String toString() {
        return strVal;
    }
}
