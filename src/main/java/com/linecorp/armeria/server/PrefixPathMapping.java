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
    private final String loggerName;
    private final String metricName;
    private final String strVal;

    PrefixPathMapping(String prefix, boolean stripPrefix) {
        prefix = ensureAbsolutePath(prefix, "prefix");
        if (!prefix.endsWith("/")) {
            prefix += '/';
        }

        this.prefix = prefix;
        this.stripPrefix = stripPrefix;
        loggerName = loggerName(prefix);
        metricName = prefix + "**";
        strVal = "prefix: " + prefix + " (stripPrefix: " + stripPrefix + ')';
    }

    @Override
    protected String doApply(String path) {
        if (!path.startsWith(prefix)) {
            return null;
        }

        return stripPrefix ? path.substring(prefix.length() - 1) : path;
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    @Override
    public String metricName() {
        return metricName;
    }

    @Override
    public int hashCode() {
        return stripPrefix ? prefix.hashCode() : -prefix.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PrefixPathMapping)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final PrefixPathMapping that = (PrefixPathMapping) obj;
        return stripPrefix == that.stripPrefix && prefix.equals(that.prefix);
    }

    @Override
    public String toString() {
        return strVal;
    }
}
