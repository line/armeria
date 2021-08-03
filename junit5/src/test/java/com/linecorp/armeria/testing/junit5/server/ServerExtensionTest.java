/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

class ServerExtensionTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void requestContextCaptor() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        client.get("/hello").aggregate().join();

        final ServiceRequestContextCaptor captor = server.requestContextCaptor();
        assertThat(captor.size()).isEqualTo(1);

        assertThat(captor.take().request().uri().getPath()).isEqualTo("/hello");
    }
}
