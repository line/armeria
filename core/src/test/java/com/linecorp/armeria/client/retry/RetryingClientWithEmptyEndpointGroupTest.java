/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EmptyEndpointGroupException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;

class RetryingClientWithEmptyEndpointGroupTest {

    @Test
    void shouldRetryEvenIfEndpointGroupIsEmpty() {
        final int numAttempts = 3;
        final WebClient client =
                WebClient.builder(SessionProtocol.HTTP, EndpointGroup.empty())
                         .decorator(RetryingClient.builder(RetryRule.builder()
                                                                    .onUnprocessed()
                                                                    .thenBackoff(Backoff.withoutDelay()))
                                                  .maxTotalAttempts(numAttempts)
                                                  .newDecorator())
                         .build();

        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            client.get("/").aggregate();
            ctx = ctxCaptor.get();
        }

        // Make sure all attempts have failed with `EmptyEndpointGroupException`.
        final RequestLog log = ctx.log().whenComplete().join();
        assertEmptyEndpointGroupException(log);

        assertThat(log.children()).hasSize(numAttempts);
        log.children().stream()
           .map(RequestLogAccess::ensureComplete)
           .forEach(RetryingClientWithEmptyEndpointGroupTest::assertEmptyEndpointGroupException);
    }

    private static void assertEmptyEndpointGroupException(RequestLog log) {
        assertThat(log.responseCause()).isInstanceOf(UnprocessedRequestException.class)
                                       .hasCauseInstanceOf(EmptyEndpointGroupException.class);
    }
}
