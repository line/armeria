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
 * under the License
 */

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import reactor.test.StepVerifier;

class EmptyContentDecodedHttpRequestTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void emptyContent() {
        final RoutingContext routingContext = mock(RoutingContext.class);
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/");
        final EmptyContentDecodedHttpRequest req =
                new EmptyContentDecodedHttpRequest(eventLoop.get(), 1, 3, headers, true, routingContext,
                                                   ExchangeType.BIDI_STREAMING, 0, 0);

        StepVerifier.create(req)
                    .expectComplete()
                    .verify();
        assertThat(req.headers()).isEqualTo(headers);
    }
}
