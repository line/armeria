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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.internal.client.ClientAttributeUtil.setUnprocessedPendingThrowable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ClientAttributeUtilTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/1", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void testUnprocessedPending() {
        final RuntimeException e = new RuntimeException();
        final WebClient webClient =
                WebClient.builder(SessionProtocol.HTTP, EndpointGroup.of())
                         .contextCustomizer(ctx -> setUnprocessedPendingThrowable(ctx, e))
                         .build();
        assertThatThrownBy(() -> webClient.blocking().get("/"))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasCause(e);
    }

    @Test
    void testUnprocessedPendingDerivedContext() {
        final RuntimeException e = new RuntimeException();
        final WebClient webClient =
                WebClient.builder(SessionProtocol.HTTP, EndpointGroup.of())
                         .contextCustomizer(ctx -> setUnprocessedPendingThrowable(ctx, e))
                         .decorator((delegate, ctx, req) -> {
                             final ClientRequestContext derived = ctx.newDerivedContext(
                                     RequestId.random(), req, null, server.httpEndpoint());
                             return delegate.execute(derived, req);
                         })
                         .build();
        assertThat(webClient.get("/1").aggregate().join().status().code()).isEqualTo(200);
    }
}
