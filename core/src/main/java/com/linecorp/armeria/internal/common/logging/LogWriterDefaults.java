/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.common.logging;

import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLogLevelMapper;
import com.linecorp.armeria.common.logging.ResponseLogLevelMapper;

public final class LogWriterDefaults {

    public static final LogLevel DEFAULT_REQUEST_LOG_LEVEL = LogLevel.DEBUG;

    public static final RequestLogLevelMapper DEFAULT_REQUEST_LOG_LEVEL_MAPPER =
            RequestLogLevelMapper.of(DEFAULT_REQUEST_LOG_LEVEL);

    public static final ResponseLogLevelMapper DEFAULT_RESPONSE_LOG_LEVEL_MAPPER =
            ResponseLogLevelMapper.of(LogLevel.DEBUG, LogLevel.WARN);

    private LogWriterDefaults() {}
}
