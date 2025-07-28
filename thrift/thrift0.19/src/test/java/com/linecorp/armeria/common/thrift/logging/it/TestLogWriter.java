/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.thrift.logging.it;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import com.linecorp.armeria.common.logging.ContentSanitizer;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;

class TestLogWriter implements LogWriter {

    private final LogFormatter logFormatter;
    private final BlockingDeque<String> blockingDeque = new LinkedBlockingDeque<>();

    TestLogWriter(ContentSanitizer<String> contentSanitizer) {
        logFormatter = LogFormatter.builderForText()
                                   .contentSanitizer(contentSanitizer)
                                   .build();
    }

    @Override
    public void logRequest(RequestOnlyLog log) {
        blockingDeque.add(logFormatter.formatRequest(log));
    }

    @Override
    public void logResponse(RequestLog log) {
        blockingDeque.add(logFormatter.formatResponse(log));
    }

    BlockingDeque<String> blockingDeque() {
        return blockingDeque;
    }
}
