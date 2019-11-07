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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextBuilder;

class RequestLogListenerInvokerTest {

    @Test
    void testInvokeOnRequestLog() throws InterruptedException {
        final AtomicInteger counter = new AtomicInteger();
        final ServiceRequestContext ctx = ServiceRequestContextBuilder.of(HttpRequest.of(HttpMethod.GET, "/"))
                                                                      .build();
        ctx.onEnter(c -> counter.incrementAndGet());
        final RequestLog log = ctx.log();
        log.addListener(l -> { /* no-op */ }, RequestLogAvailability.COMPLETE);
        log.addListener(l -> { /* no-op */ }, RequestLogAvailability.COMPLETE);

        ctx.logBuilder().endRequest();
        // There's no listener for RequestLogAvailability.REQUEST_END, so onEnter is not called.
        assertThat(counter.get()).isZero();

        ctx.logBuilder().endResponse();
        assertThat(counter.get()).isOne();
    }
}
