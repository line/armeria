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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

public class ResponseEntityUtilTest {
    @Test
    void statusIsContentAlwaysEmpty() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.NO_CONTENT);
        final ResponseEntity<Void> result = ResponseEntity.of(headers);

        final ResponseHeaders actual = ResponseEntityUtil.buildResponseHeaders(ctx, result);
        assertThat(actual).isEqualTo(headers);
        assertThat(actual.contentType()).isNull();

        verifyNoInteractions(ctx);
    }

    @Test
    void contentTypeIsNotNull() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

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

        verifyNoInteractions(ctx);
    }

    @Test
    void useNegotiatedResponseMediaType() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        when(ctx.negotiatedResponseMediaType()).thenReturn(MediaType.JSON_UTF_8);

        final ResponseEntity<Void> result = ResponseEntity.of(ResponseHeaders.of(HttpStatus.OK));
        final ResponseHeaders actual = ResponseEntityUtil.buildResponseHeaders(ctx, result);
        assertThat(actual.contentType()).isEqualTo(MediaType.JSON_UTF_8);
    }
}
