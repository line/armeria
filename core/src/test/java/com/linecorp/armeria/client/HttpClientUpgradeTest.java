/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpClientUpgradeTest {

    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void numConnections() {
        try (ClientFactory factory = ClientFactory.builder()
                                                        .useHttp2Preface(false)
                                                        .build()) {
            final WebClient client = WebClient.builder(server.httpUri()).factory(factory).build();
            // Before https://github.com/line/armeria/pull/5162 is applied,
            // the following exception was raised and caught by DefaultHttp2Connection:
            //
            // ERROR i.n.h.c.http2.DefaultHttp2Connection - Caught Throwable from listener onStreamClosed.
            // java.lang.AssertionError: null
            //  at com.linecorp.armeria.client.HttpSessionHandler.isAcquirable(HttpSessionHandler.java:290)
            //  at com.linecorp.armeria.client.AbstractHttpResponseDecoder.needsToDisconnectNow(Abstract...)
            //  at com.linecorp.armeria.client.Http2ResponseDecoder.shouldSendGoAway(Http2ResponseDecoder...)
            //  ..
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
        }
    }
}
