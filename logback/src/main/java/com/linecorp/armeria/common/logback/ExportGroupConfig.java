/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.logback;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.logging.ExportGroup;
import com.linecorp.armeria.common.logging.ExportGroupBuilder;

/**
 * Bridge class for Logback configuration.
 *
 * @see RequestContextExportingAppender#setExportGroup(ExportGroupConfig)
 */
public final class ExportGroupConfig {

    private final ExportGroupBuilder builder = ExportGroup.builder();

    /**
     * Specifies a prefix of the default export group.
     * Note: this method is meant to be used for XML configuration.
     */
    public void setPrefix(String prefix) {
        requireNonNull(prefix, "prefix");
        checkArgument(!prefix.isEmpty(), "prefix must not be empty");
        builder.prefix(prefix);
    }

    /**
     * Adds the property represented by the specified MDC key to the export list.
     * Note: this method is meant to be used for XML configuration.
     */
    public void setExport(String mdcKey) {
        requireNonNull(mdcKey, "mdcKey");
        checkArgument(!mdcKey.isEmpty(), "mdcKey must not be empty");
        builder.keyPattern(mdcKey);
    }

    /**
     * Adds the properties represented by the specified comma-separated MDC keys to the export list.
     * Note: this method is meant to be used for XML configuration.
     */
    public void setExports(String mdcKeys) {
        requireNonNull(mdcKeys, "mdcKeys");
        checkArgument(!mdcKeys.isEmpty(), "mdcKeys must not be empty");
        builder.keyPatterns(mdcKeys);
    }

    /**
     * Returns {@link ExportGroup}.
     */
    ExportGroup build() {
        return builder.build();
    }
}
