/*
 * Copyright 2019 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.logging.AccessLogWriter;

class ServiceConfigBuilderTest {

    @ParameterizedTest
    @CsvSource({
            "testLogger, 10, 50, true",
            ",,,"
    })
    void copyOf(String loggerName, Long maxRequestLength, Long requestTimeoutMillis, Boolean verboseResponse) {
        final Service<HttpRequest, HttpResponse> service = (ctx, req) -> HttpResponse.of(HttpStatus.OK);
        final Route route = Route.builder().path("/foo").build();
        final AccessLogWriter accessLogWriter = AccessLogWriter.disabled();
        final ServiceConfigBuilder original = new ServiceConfigBuilder(route, service)
                .accessLogWriter(accessLogWriter, true)
                .requestContentPreviewerFactory(ContentPreviewerFactory.disabled())
                .responseContentPreviewerFactory(ContentPreviewerFactory.disabled());
        if (loggerName != null) {
            original.loggerName(loggerName);
        }
        if (maxRequestLength != null) {
            original.maxRequestLength(maxRequestLength);
        }
        if (verboseResponse != null) {
            original.verboseResponses(verboseResponse);
        }
        if (requestTimeoutMillis != null) {
            original.requestTimeoutMillis(requestTimeoutMillis);
        }

        final ServiceConfigBuilder copied = ServiceConfigBuilder.copyOf(original);
        assertThat(copied.maxRequestLength()).isEqualTo(maxRequestLength);
        assertThat(copied.accessLogWriter()).isEqualTo(accessLogWriter);
        assertThat(copied.requestContentPreviewerFactory()).isEqualTo(ContentPreviewerFactory.disabled());
        assertThat(copied.responseContentPreviewerFactory()).isEqualTo(ContentPreviewerFactory.disabled());
        assertThat(copied.verboseResponses()).isEqualTo(verboseResponse);
        assertThat(copied.requestTimeoutMillis()).isEqualTo(requestTimeoutMillis);

        // build config to verify route, service and loggerName
        if (maxRequestLength != null && requestTimeoutMillis != null && verboseResponse != null) {
            final ServiceConfig serviceConfig = copied.build();
            assertThat(serviceConfig.route()).isEqualTo(route);
            assertThat((Service<?, ?>) serviceConfig.service()).isEqualTo(service);
            if (loggerName == null) {
                assertThat(serviceConfig.loggerName()).isEmpty();
            } else {
                assertThat(serviceConfig.loggerName()).contains(loggerName);
            }
            assertThat(serviceConfig.shutdownAccessLogWriterOnStop()).isEqualTo(true);
        }
    }
}
