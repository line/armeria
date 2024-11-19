/*
 * Copyright 2024 LINE Corporation
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

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * Utility class for {@link AccessLogWriter}.
 */
final class AccessLogWriterUtil {

    /**
     * Writes an access log if the {@link TransientServiceOption#WITH_ACCESS_LOGGING} option is enabled
     * for the {@link ServiceConfig#transientServiceOptions()} and the {@link ServiceConfig#accessLogWriter()}
     * is not {@link AccessLogWriter#disabled()} for the given {@link ServiceRequestContext#config()}.
     */
    static void maybeWriteAccessLog(ServiceRequestContext reqCtx) {
        final ServiceConfig config = reqCtx.config();
        if (shouldWriteAccessLog(config)) {
            reqCtx.log().whenComplete().thenAccept(log -> {
                try (SafeCloseable ignored = reqCtx.push()) {
                    config.accessLogWriter().log(log);
                }
            });
        }
    }

    /**
     * Returns whether an access log should be written.
     *
     */
    private static boolean shouldWriteAccessLog(ServiceConfig config) {
        return config.accessLogWriter() != AccessLogWriter.disabled() &&
                config.transientServiceOptions().contains(TransientServiceOption.WITH_ACCESS_LOGGING);
    }

    private AccessLogWriterUtil() {}
}
