/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.logging;

/**
 * Access log writers.
 *
 * @deprecated Use the factory methods in {@link AccessLogWriter}.
 */
@Deprecated
public final class AccessLogWriters {

    /**
     * Returns an access log writer with a common format.
     */
    public static AccessLogWriter common() {
        return AccessLogWriter.common();
    }

    /**
     * Returns an access log writer with a combined format.
     */
    public static AccessLogWriter combined() {
        return AccessLogWriter.combined();
    }

    /**
     * Returns disabled access log writer.
     */
    public static AccessLogWriter disabled() {
        return AccessLogWriter.disabled();
    }

    /**
     * Returns an access log writer with the specified {@code formatStr}.
     */
    public static AccessLogWriter custom(String formatStr) {
        return AccessLogWriter.custom(formatStr);
    }

    private AccessLogWriters() {}
}
