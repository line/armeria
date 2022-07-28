/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.HttpHeaderNames.CACHE_CONTROL;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
import static com.linecorp.armeria.common.HttpHeaderNames.DATE;
import static com.linecorp.armeria.common.HttpHeaderNames.IF_MODIFIED_SINCE;
import static com.linecorp.armeria.common.HttpHeaderNames.LAST_MODIFIED;
import static com.linecorp.armeria.common.HttpHeaderNames.of;
import static com.linecorp.armeria.common.MediaType.ANY_APPLICATION_TYPE;
import static com.linecorp.armeria.common.MediaType.ANY_AUDIO_TYPE;
import static com.linecorp.armeria.common.MediaType.ANY_TEXT_TYPE;
import static com.linecorp.armeria.common.MediaType.ANY_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

class HttpHeadersTest {

    @Test
    void testCaseInsensitiveHeaderNames() throws Exception {
        final HttpHeaders headers = HttpHeaders.of(of("header1"), "value1",
                                                   of("HEADER2"), "value2",
                                                   of("Header3"), "VALUE3");

        assertThat(headers.get(of("HeAdEr1"))).isEqualTo("value1");
        assertThat(headers.get(of("header2"))).isEqualTo("value2");
        assertThat(headers.get(of("HEADER3"))).isEqualTo("VALUE3");

        assertThat(headers.names())
                .containsExactlyInAnyOrder(of("header1"), of("header2"), of("header3"));
    }

    @Test
    void testInvalidHeaderName() throws Exception {
        assertThatThrownBy(() -> HttpHeaders.of(null, "value1"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> HttpHeaders.of(of(""), "value1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contentType() {
        final HttpHeadersBuilder headers = HttpHeaders.builder();

        headers.contentType(ANY_TYPE);
        assertThat(headers.contentType()).isSameAs(ANY_TYPE);
        assertThat(headers.get(CONTENT_TYPE)).isEqualTo(ANY_TYPE.toString());

        headers.contentType(ANY_APPLICATION_TYPE);
        assertThat(headers.contentType()).isSameAs(ANY_APPLICATION_TYPE);
        assertThat(headers.get(CONTENT_TYPE)).isEqualTo(ANY_APPLICATION_TYPE.toString());

        headers.setObject(CONTENT_TYPE, ANY_TEXT_TYPE);
        assertThat(headers.contentType()).isSameAs(ANY_TEXT_TYPE);
        assertThat(headers.get(CONTENT_TYPE)).isEqualTo(ANY_TEXT_TYPE.toString());

        headers.set(CONTENT_TYPE, ANY_AUDIO_TYPE.toString());
        assertThat(headers.contentType()).isSameAs(ANY_AUDIO_TYPE);
        assertThat(headers.get(CONTENT_TYPE)).isEqualTo(ANY_AUDIO_TYPE.toString());
    }

    @Test
    void testSetObject() {
        final String expectedDate = "Mon, 03 Dec 2007 10:15:30 GMT";
        final Instant instant = Instant.parse("2007-12-03T10:15:30.00Z");
        final Date date = new Date(instant.toEpochMilli());
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(instant.toEpochMilli());

        final HttpHeadersBuilder headers = HttpHeaders.builder();
        headers.setObject(DATE, date);
        headers.setObject(LAST_MODIFIED, instant);
        headers.setObject(IF_MODIFIED_SINCE, calendar);
        headers.setObject(CACHE_CONTROL, ServerCacheControl.DISABLED);
        headers.setObject(CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);

        assertThat(headers.get(DATE)).isEqualTo(expectedDate);
        assertThat(headers.get(LAST_MODIFIED)).isEqualTo(expectedDate);
        assertThat(headers.get(IF_MODIFIED_SINCE)).isEqualTo(expectedDate);
        assertThat(headers.get(CACHE_CONTROL)).isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(headers.get(CONTENT_TYPE)).isEqualTo("text/plain; charset=utf-8");
    }
}
