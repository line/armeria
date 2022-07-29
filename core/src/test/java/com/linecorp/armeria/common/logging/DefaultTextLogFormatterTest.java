/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.Functions;
import com.linecorp.armeria.server.ServiceRequestContext;

class DefaultTextLogFormatterTest {

    @Test
    void formatRequest() {
        final LogFormatter logFormatter = LogFormatter.ofText();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/format"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endRequest();
        final LogSanitizer sanitizer = LogSanitizer.ofRequestLogSanitizer(
                Functions.second(),
                Functions.second(),
                Functions.second()
        );
        final String requestLog = logFormatter.formatRequest(log, sanitizer);
        assertThat(requestLog)
                .matches("^\\{startTime=.+, length=.+, duration=.+, scheme=.+, name=.+, headers=.+}$");
    }

    @Test
    void formatResponse() {
        final LogFormatter logFormatter = LogFormatter.ofText();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/format"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endResponse();
        final LogSanitizer sanitizer = LogSanitizer.ofResponseLogSanitizer(
                Functions.second(),
                Functions.second(),
                Functions.second()
        );
        final String responseLog = logFormatter.formatResponse(log, sanitizer);
        assertThat(responseLog)
                .matches("^\\{startTime=.+, length=.+, duration=.+, totalDuration=.+, headers=.+}$");
    }
}
