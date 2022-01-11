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

class ServerExtensionWithSecuredTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tlsSelfSigned();
            sb.service("/backend", (ctx, req) -> HttpResponse.of(200));
            sb.service("/frontend", (ctx, req) -> server.webClient().get("/backend"));
        }
    };

    @Test
    void requestContextCaptor() throws InterruptedException {
        final WebClient client = server.webClient();
        client.get("/frontend").aggregate().join();

        final ServiceRequestContextCaptor captor = server.requestContextCaptor();
        assertThat(captor.size()).isEqualTo(2);
        assertThat(captor.take().request().uri().getPath()).isEqualTo("/frontend");
        assertThat(captor.take().request().uri().getPath()).isEqualTo("/backend");
    }
}
