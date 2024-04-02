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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class VirtualThreadTest {

    static AtomicReference<Thread> serviceWorkerThread = new AtomicReference<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                serviceWorkerThread.set(Thread.currentThread());
                return HttpResponse.of(200);
            });
        }
    };

    @BeforeEach
    void beforeEach() {
        serviceWorkerThread.set(null);
    }

    @Test
    void testBasicCase() {
        final AggregatedHttpResponse res = server.blockingWebClient().get("/");
        assertThat(res.status().code()).isEqualTo(200);
        final Thread thread = serviceWorkerThread.get();
        assertThat(thread.isVirtual()).isTrue();
    }
}
