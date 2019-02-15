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

import java.nio.charset.Charset;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;

public class ContentPreviewerFactoryTest {
    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    RequestContext ctx;

    private static HttpHeaders headers(MediaType contentType) {
        return HttpHeaders.of().contentType(contentType);
    }

    private static HttpHeaders headers(String contentType) {
        return headers(MediaType.parse(contentType));
    }

    private static final HttpHeaders textHeader = headers(MediaType.PLAIN_TEXT_UTF_8);
    private static final HttpHeaders jsonHeader = headers(MediaType.JSON);
    private static final HttpHeaders pXmlHeader = headers(MediaType.SOAP_XML_UTF_8);

    @Test
    public void testCreating() {
        assertThat(ContentPreviewerFactory.of(ContentPreviewerFactory.ofText(10))).isInstanceOf(
                TextualContentPreviewerFactory.class);
        assertThat(ContentPreviewerFactory
                           .of(ContentPreviewerFactory.ofText(10),
                               ContentPreviewerFactory.of(() -> ContentPreviewer.ofText(10), MediaType.JSON)))
                .isInstanceOf(CompositeContentPreviewerFactory.class);
        assertThat(ContentPreviewerFactory
                           .of(ContentPreviewerFactory.of(() -> ContentPreviewer.ofText(10), MediaType.JSON),
                               ContentPreviewerFactory.of(() -> ContentPreviewer.ofText(1), "text/*")))
                .isInstanceOf(MappedContentPreviewerFactory.class);
    }

    @Test
    public void testOfText() {
        assertThat(ContentPreviewerFactory.ofText(10).get(ctx, textHeader))
                .isInstanceOf(StringContentPreviewer.class);
        assertThat(ContentPreviewerFactory.ofText(10).get(ctx, jsonHeader))
                .isInstanceOf(StringContentPreviewer.class);
        assertThat(ContentPreviewerFactory.ofText(10).get(ctx, pXmlHeader))
                .isInstanceOf(StringContentPreviewer.class);
        // returns disabled when length == 0
        assertThat(ContentPreviewerFactory.ofText(0)).isSameAs(ContentPreviewerFactory.disabled());
        assertThat(ContentPreviewerFactory.ofText(0, Charset.defaultCharset(), "text/plain")).isSameAs(
                ContentPreviewerFactory.disabled());
    }

    @Test
    public void testComposite() {
        ContentPreviewerFactory factory = ContentPreviewerFactory.of(
                ContentPreviewerFactory.ofText(20, Charset.defaultCharset(), "text/test"),
                ContentPreviewerFactory.ofText(10),
                // shouldn't get those.
                ContentPreviewerFactory.ofText(30, Charset.defaultCharset(), "text/aaa")
        );
        assertThat(factory.get(ctx, textHeader)).isInstanceOf(StringContentPreviewer.class);
        assertThat(((StringContentPreviewer) factory.get(ctx, textHeader)).length()).isEqualTo(10);
        assertThat(((StringContentPreviewer) factory.get(ctx, headers("text/test"))).length())
                .isEqualTo(20);
        assertThat(((StringContentPreviewer) factory.get(ctx, headers("text/aaa"))).length())
                .isEqualTo(10);
        // returns disabled if all components are null.
        assertThat(ContentPreviewerFactory.of(ContentPreviewerFactory.disabled(),
                                              ContentPreviewerFactory.disabled()))
                .isEqualTo(ContentPreviewerFactory.disabled());
        // returns the left one if others are disabled.
        final ContentPreviewerFactory f = ContentPreviewerFactory.ofText(10);
        assertThat(ContentPreviewerFactory.of(ContentPreviewerFactory.disabled(), f))
                .isSameAs(f);
        assertThat(((CompositeContentPreviewerFactory) ContentPreviewerFactory.of(
                factory, ContentPreviewerFactory.ofText(10))).factoryList).hasSize(4);
    }

    @Test
    public void testMapped() {
        ContentPreviewerFactory factory = ContentPreviewerFactory.of(
                ContentPreviewerFactory.ofText(10, Charset.defaultCharset(), MediaType.JSON),
                ContentPreviewerFactory.ofText(20, Charset.defaultCharset(), MediaType.ANY_TEXT_TYPE),
                // shouldn't get those.
                ContentPreviewerFactory.ofText(30, Charset.defaultCharset(), MediaType.JSON),
                ContentPreviewerFactory.ofText(40, Charset.defaultCharset(), MediaType.JSON),
                ContentPreviewerFactory.ofText(50, Charset.defaultCharset(), MediaType.JSON),
                ContentPreviewerFactory.ofText(60, Charset.defaultCharset(), MediaType.JSON)
        );
        assertThat(((StringContentPreviewer) factory.get(ctx, textHeader)).length()).isEqualTo(20);
        assertThat(((StringContentPreviewer) factory.get(ctx, jsonHeader)).length()).isEqualTo(10);
    }
}
