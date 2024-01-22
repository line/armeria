/*
 *  Copyright 2024 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.HttpResult;

class HttpResultUtilTest {

    @Test
    void shouldReuseResponseHeaders() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

        final ResponseHeaders headers = ResponseHeaders
                .builder(HttpStatus.OK)
                .contentType(MediaType.PLAIN_TEXT_UTF_8)
                .add("foo", "bar")
                .build();
        final HttpResult<Integer> result = HttpResult.of(headers, 123);

        final ResponseHeaders actual = HttpResultUtil.buildResponseHeaders(ctx, result);
        assertThat(actual).isEqualTo(headers);
        assertThat(actual.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(actual.get("foo")).isEqualTo("bar");

        verifyNoInteractions(ctx);
    }

    @Test
    void shouldNotAddContentTypeWhenNoContent() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.NO_CONTENT);
        final HttpResult<Integer> result = HttpResult.of(headers, 123);

        final ResponseHeaders actual = HttpResultUtil.buildResponseHeaders(ctx, result);
        assertThat(actual).isEqualTo(headers);
        assertThat(actual.contentType()).isNull();

        verifyNoInteractions(ctx);
    }

    @Test
    void shouldNegotiateContentType() {
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        when(ctx.negotiatedResponseMediaType()).thenReturn(MediaType.JSON_UTF_8);

        final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK, "foo", "bar");
        final HttpResult<Integer> result = HttpResult.of(headers, 123);

        final ResponseHeaders actual = HttpResultUtil.buildResponseHeaders(ctx, result);
        assertThat(actual.status()).isEqualTo(HttpStatus.OK);
        assertThat(actual.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(actual.get("foo")).isEqualTo("bar");
    }

    @Test
    void shouldAddStatusFromAnnotatedService() {
        final AnnotatedService service = mock(AnnotatedService.class);
        when(service.as(AnnotatedService.class)).thenReturn(service);
        when(service.defaultStatus()).thenReturn(HttpStatus.ACCEPTED);

        final ServiceConfig config = mock(ServiceConfig.class);
        when(config.service()).thenReturn(service);

        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        when(ctx.config()).thenReturn(config);
        when(ctx.negotiatedResponseMediaType()).thenReturn(MediaType.PLAIN_TEXT_UTF_8);

        final HttpHeaders headers = HttpHeaders.of("foo", "bar");
        final HttpResult<Integer> result = HttpResult.of(headers, 123);

        final ResponseHeaders actual = HttpResultUtil.buildResponseHeaders(ctx, result);
        assertThat(actual.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(actual.contentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(actual.get("foo")).isEqualTo("bar");
    }

    @Test
    void shouldUseDefaultStatusWhenNotAnnotatedService() {
        final HttpService service = mock(HttpService.class);
        when(service.as(AnnotatedService.class)).thenReturn(null);

        final ServiceConfig config = mock(ServiceConfig.class);
        when(config.service()).thenReturn(service);

        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        when(ctx.config()).thenReturn(config);
        when(ctx.negotiatedResponseMediaType()).thenReturn(MediaType.JSON_UTF_8);

        final HttpHeaders headers = HttpHeaders.of("foo", "bar");
        final HttpResult<Integer> result = HttpResult.of(headers, 123);

        final ResponseHeaders actual = HttpResultUtil.buildResponseHeaders(ctx, result);
        assertThat(actual.status()).isEqualTo(HttpStatus.OK);
        assertThat(actual.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(actual.get("foo")).isEqualTo("bar");
    }
}
