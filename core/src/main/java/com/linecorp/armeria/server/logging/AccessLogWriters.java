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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.server.logging.AccessLogFormats.parseCustom;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Consumer;

import com.linecorp.armeria.common.logging.RequestLog;

/**
 * Access log writers.
 */
public final class AccessLogWriters {

    /**
     * Returns an access log writer with a common format.
     */
    public static Consumer<RequestLog> common() {
        return requestLog -> AccessLogger.write(AccessLogFormats.COMMON, requestLog);
    }

    /**
     * Returns an access log writer with a combined format.
     */
    public static Consumer<RequestLog> combined() {
        return requestLog -> AccessLogger.write(AccessLogFormats.COMBINED, requestLog);
    }

    /**
     * Returns disabled access log writer.
     */
    public static Consumer<RequestLog> disabled() {
        return requestLog -> { /* No operation. */ };
    }

    /**
     * Returns an access log writer with the specified {@code formatStr}.
     */
    public static Consumer<RequestLog> custom(String formatStr) {
        final List<AccessLogComponent> accessLogFormat = parseCustom(requireNonNull(formatStr, "formatStr"));
        checkArgument(!accessLogFormat.isEmpty(), "Invalid access log format string: " + formatStr);
        return requestLog -> AccessLogger.write(accessLogFormat, requestLog);
    }

    private AccessLogWriters() {}
}
