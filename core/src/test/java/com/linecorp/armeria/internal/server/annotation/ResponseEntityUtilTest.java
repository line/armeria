/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.RoutingResult;
import com.linecorp.armeria.server.ServiceRequestContext;

class ResponseEntityUtilTest {
    @Test
    void statusIsContentAlwaysEmpty() {
        final ServiceRequestContext ctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.NO_CONTENT);
        final ResponseEntity<Void> result = ResponseEntity.of(headers);

        final ResponseHeaders actual = ResponseEntityUtil.buildResponseHeaders(ctx, result);
        assertThat(actual).isEqualTo(headers);
        assertThat(actual.contentType()).isNull();
    }

    @Test
    void contentTypeIsNotNull() {
        final ServiceRequestContext ctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        final ResponseHeaders headers = ResponseHeaders
                .builder(HttpStatus.OK)
                .contentType(MediaType.PLAIN_TEXT_UTF_8)
                .add("foo", "bar")
                .build();
        final ResponseEntity<Void> result = ResponseEntity.of(headers);

        final ResponseHeaders actual = ResponseEntityUtil.buildResponseHeaders(ctx, result);
        assertThat(actual).isEqualTo(headers);
        assertThat(actual.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(actual.get("foo")).isEqualTo("bar");
    }

    @Test
    void useNegotiatedResponseMediaType() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final RoutingResult routingResult = RoutingResult.builder()
                                                         .path("/foo")
                                                         .negotiatedResponseMediaType(MediaType.JSON_UTF_8)
                                                         .build();
        final ServiceRequestContext ctx = ServiceRequestContext.builder(request)
                                                               .routingResult(routingResult)
                                                               .build();

        final ResponseEntity<Void> result = ResponseEntity.of(ResponseHeaders.of(HttpStatus.OK));
        final ResponseHeaders actual = ResponseEntityUtil.buildResponseHeaders(ctx, result);
        assertThat(actual.contentType()).isEqualTo(MediaType.JSON_UTF_8);
    }
}
