/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.testing.junit5.server;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.fail;

class ServiceRequestContextCaptorTest {
    static final ServiceRequestContextCaptor captor = new ServiceRequestContextCaptor();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", (ctx, req) -> HttpResponse.of(200));
            sb.decorator(captor.decorator());
        }
    };

    @BeforeEach
    void clearCaptor() {
        captor.clear();
    }

    @Test
    void shouldCaptureContexts() {
        WebClient client = WebClient.of(server.httpUri());
        client.get("/hello").aggregate().join();
        assertThat(captor.size()).isEqualTo(1);

        client.get("/hello2").aggregate().join();
        assertThat(captor.size()).isEqualTo(2);

        try {
            assertThat(captor.take().request().uri().getPath()).isEqualTo("/hello");
            assertThat(captor.take().request().uri().getPath()).isEqualTo("/hello2");
        } catch (InterruptedException e) {
            fail("Should not fail");
        }
    }

    @Test
    void shouldClear() {
        WebClient client = WebClient.of(server.httpUri());
        client.get("/hello").aggregate().join();
        assertThat(captor.size()).isEqualTo(1);

        captor.clear();

        client.get("/hello2").aggregate().join();
        assertThat(captor.size()).isEqualTo(1);

        try {
            assertThat(captor.take().request().uri().getPath()).isEqualTo("/hello2");
        } catch (InterruptedException e) {
            fail("Should not fail");
        }
    }
}
