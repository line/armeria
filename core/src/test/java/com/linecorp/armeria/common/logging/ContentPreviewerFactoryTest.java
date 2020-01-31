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

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;

class ContentPreviewerFactoryTest {

    private static final RequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    private static HttpHeaders headers(MediaType contentType) {
        return HttpHeaders.of(HttpHeaderNames.CONTENT_TYPE, contentType);
    }

    private static final HttpHeaders textHeader = headers(MediaType.PLAIN_TEXT_UTF_8);
    private static final HttpHeaders jsonHeader = headers(MediaType.JSON);

    @Test
    void testOfText() {
        ContentPreviewer contentPreviewer = ContentPreviewerFactory.ofText(10).get(ctx, textHeader);
        checkProduced(contentPreviewer);

        contentPreviewer = ContentPreviewerFactory.ofText(10).get(ctx, jsonHeader);
        checkProduced(contentPreviewer);

        contentPreviewer = ContentPreviewerFactory.ofText(10).get(ctx, headers(MediaType.SOAP_XML_UTF_8));
        checkProduced(contentPreviewer);

        contentPreviewer = ContentPreviewerFactory.ofText(10).get(ctx, headers(MediaType.FORM_DATA));
        checkProduced(contentPreviewer);

        contentPreviewer = ContentPreviewerFactory.ofText(10).get(
                ctx, HttpHeaders.of(HttpHeaderNames.CONTENT_TYPE, "my/type; charset=UTF-8"));
        checkProduced(contentPreviewer);

        assertThat(ContentPreviewerFactory.ofText(10).get(ctx, headers(MediaType.ANY_AUDIO_TYPE)))
                .isSameAs(ContentPreviewer.disabled());
    }

    private static void checkProduced(ContentPreviewer contentPreviewer) {
        contentPreviewer.onData(HttpData.ofUtf8("hello!"));
        assertThat(contentPreviewer.produce()).isEqualTo("hello!");
    }

    @Test
    void producedPreviewDoesNotExceedMaxLength() {
        final ContentPreviewer contentPreviewer = ContentPreviewerFactory.ofText(4).get(ctx, textHeader);
        contentPreviewer.onData(HttpData.ofUtf8("hello!"));
        assertThat(contentPreviewer.produce()).isEqualTo("hell");
    }

    @Test
    void mappedContentPreviewerFactory() {
        final ContentPreviewerFactory factory = ContentPreviewerFactory.of(
                ImmutableMap.of(MediaType.JSON,
                                charset -> ContentPreviewer.ofText(10, charset),
                                MediaType.ANY_TEXT_TYPE,
                                charset -> ContentPreviewer.ofText(20, charset)));

        assertThat(((StringContentPreviewer) factory.get(ctx, textHeader)).maxLength()).isEqualTo(20);
        assertThat(((StringContentPreviewer) factory.get(ctx, jsonHeader)).maxLength()).isEqualTo(10);
    }
}
