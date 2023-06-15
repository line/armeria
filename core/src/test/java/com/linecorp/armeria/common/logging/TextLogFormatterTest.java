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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

class TextLogFormatterTest {

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void formatRequest(boolean containContext) {
        final LogFormatter logFormatter = LogFormatter.builderForText().includeContext(containContext).build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/format"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endRequest();
        final String requestLog = logFormatter.formatRequest(log);
        final String regex =
                "Request: .*\\{startTime=.+, length=.+, duration=.+, scheme=.+, name=.+, headers=.+}$";
        if (containContext) {
            assertThat(requestLog).matches("\\[sreqId=.* " + regex);
        } else {
            assertThat(requestLog).matches(regex);
        }
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void formatResponse(boolean containContext) {
        final LogFormatter logFormatter = LogFormatter.builderForText().includeContext(containContext).build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/format"));
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endResponse();
        final String responseLog = logFormatter.formatResponse(log);
        final String regex =
                "Response: .*\\{startTime=.+, length=.+, duration=.+, totalDuration=.+, headers=.+}$";
        if (containContext) {
            assertThat(responseLog).matches("\\[sreqId=.* " + regex);
        } else {
            assertThat(responseLog).matches(regex);
        }
    }
}
