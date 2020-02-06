/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeSet;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

class ContentPreviewerFactoryTest {

    private static final RequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void testOfText() {
        final ContentPreviewerFactory factory = ContentPreviewerFactory.text(10);
        ContentPreviewer contentPreviewer = factory.requestContentPreviewer(
                ctx, reqHeaders(MediaType.PLAIN_TEXT_UTF_8));
        checkProduced(contentPreviewer);

        contentPreviewer = factory.responseContentPreviewer(ctx, resHeaders(MediaType.PLAIN_TEXT_UTF_8));
        checkProduced(contentPreviewer);

        contentPreviewer = factory.requestContentPreviewer(ctx, reqHeaders(MediaType.JSON));
        checkProduced(contentPreviewer);

        contentPreviewer = factory.requestContentPreviewer(ctx, reqHeaders(MediaType.SOAP_XML_UTF_8));
        checkProduced(contentPreviewer);

        contentPreviewer = factory.requestContentPreviewer(ctx, reqHeaders(MediaType.FORM_DATA));
        checkProduced(contentPreviewer);

        contentPreviewer = factory.requestContentPreviewer(
                ctx, RequestHeaders.of(HttpMethod.POST, "/",
                                       HttpHeaderNames.CONTENT_TYPE, "my/type; charset=UTF-8"));
        checkProduced(contentPreviewer);

        contentPreviewer = factory.requestContentPreviewer(ctx, reqHeaders(MediaType.BASIC_AUDIO));
        contentPreviewer.onData(HttpData.ofUtf8("hello!"));
        assertThat(contentPreviewer.produce()).isNull();
    }

    private static void checkProduced(ContentPreviewer contentPreviewer) {
        contentPreviewer.onData(HttpData.ofUtf8("hello!"));
        assertThat(contentPreviewer.produce()).isEqualTo("hello!");
    }

    @Test
    void producedPreviewDoesNotExceedMaxLength() {
        final ContentPreviewerFactory factory = ContentPreviewerFactory.builder().maxLength(4).binary(
                MediaTypeSet.of(MediaType.BASIC_AUDIO), (headers, byteBuf) -> "abcde").build();

        final ContentPreviewer contentPreviewer = factory.requestContentPreviewer(
                ctx, reqHeaders(MediaType.PLAIN_TEXT_UTF_8));
        contentPreviewer.onData(HttpData.ofUtf8("hello!"));
        assertThat(contentPreviewer.produce()).isEqualTo("hell");

        final ContentPreviewer contentPreviewer2 = factory.requestContentPreviewer(
                ctx, reqHeaders(MediaType.BASIC_AUDIO));
        contentPreviewer2.onData(HttpData.ofUtf8("hello!"));
        assertThat(contentPreviewer2.produce()).isEqualTo("abcd");
    }

    private static RequestHeaders reqHeaders(MediaType contentType) {
        return RequestHeaders.of(HttpMethod.POST, "/", HttpHeaderNames.CONTENT_TYPE, contentType);
    }

    private static ResponseHeaders resHeaders(MediaType contentType) {
        return ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_TYPE, contentType);
    }
}
