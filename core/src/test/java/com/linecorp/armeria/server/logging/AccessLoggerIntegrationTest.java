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

package com.linecorp.armeria.server.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AccessLoggerIntegrationTest {

    private static final AtomicReference<RequestContext> CTX_REF = new AtomicReference<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.accessLogWriter(new AccessLogWriter() {
                @Override
                public void log(RequestLog log) {
                    CTX_REF.set(RequestContext.currentOrNull());
                }
            }, false);
        }
    };

    @BeforeEach
    void beforeEach() {
        CTX_REF.set(null);
    }

    @Test
    void testAccessLogger() throws Exception {
        assertThat(server.blockingWebClient().get("/").status().code()).isEqualTo(200);
        assertThat(server.requestContextCaptor().size()).isEqualTo(1);
        final ServiceRequestContext ctx = server.requestContextCaptor().poll();
        assertThat(ctx).isNotNull();
        await().untilAsserted(() -> assertThat(CTX_REF).hasValue(ctx));
    }
}
