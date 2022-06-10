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

package com.linecorp.armeria.server.encoding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceRequestContext;

class EncodingServiceTest {

    @Test
    void exchangeType() {
        final EncodingService encodingService = EncodingService.newDecorator().apply(new HttpService() {
            @Override
            public ExchangeType exchangeType(RoutingContext routingContext) {
                return ExchangeType.UNARY;
            }

            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                return null;
            }
        });

        final ExchangeType exchangeType = encodingService.exchangeType(null);

        // Should not return ExchangeType.UNARY to avoid aggregation and preserve the compressed chunks.
        assertThat(exchangeType).isEqualTo(ExchangeType.BIDI_STREAMING);
    }
}
