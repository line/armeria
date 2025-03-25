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
package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

class ResponseLogLevelMapperTest {

    @MethodSource("contexts")
    @ParameterizedTest
    void foo(RequestContext ctx, int status) {
        final ResponseLogLevelMapper logLevelMapper = ResponseLogLevelMapper.of(LogLevel.DEBUG, LogLevel.WARN);
        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.endRequest();
        logBuilder.responseHeaders(ResponseHeaders.of(status));
        logBuilder.endResponse();
        final LogLevel expected = status == 200 ? LogLevel.DEBUG : LogLevel.WARN;
        assertThat(logLevelMapper.apply(ctx.log().ensureComplete())).isSameAs(expected);
    }

    private static Stream<Arguments> contexts() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        return Stream.of(
                Arguments.of(ClientRequestContext.of(request), 200),
                Arguments.of(ServiceRequestContext.of(request), 200),
                Arguments.of(ClientRequestContext.of(request), 500),
                Arguments.of(ServiceRequestContext.of(request), 500)
        );
    }
}
