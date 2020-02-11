/*
 *  Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.MediaType.ANY_AUDIO_TYPE;
import static com.linecorp.armeria.common.MediaType.ANY_TYPE;
import static com.linecorp.armeria.common.MediaType.HTML_UTF_8;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static com.linecorp.armeria.common.MediaType.WEBM_VIDEO;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class MediaTypeSetTest {

    @Test
    void getters() {
        final MediaTypeSet set = MediaTypeSet.of(HTML_UTF_8, PLAIN_TEXT_UTF_8);
        assertThat(set).containsOnly(HTML_UTF_8, PLAIN_TEXT_UTF_8).hasSize(2);
    }

    @Test
    void matchList() {
        final MediaTypeSet set = MediaTypeSet.of(HTML_UTF_8, PLAIN_TEXT_UTF_8);

        // No ranges
        assertThat(set.match(ImmutableList.of())).isNull();

        // One match
        assertThat(set.match(PLAIN_TEXT_UTF_8)).isEqualTo(PLAIN_TEXT_UTF_8);
        assertThat(set.match(HTML_UTF_8)).isEqualTo(HTML_UTF_8);

        // More than one range
        assertThat(set.match(HTML_UTF_8.withParameter("q", "0.5"), PLAIN_TEXT_UTF_8))
                .isEqualTo(PLAIN_TEXT_UTF_8);

        // Wildcard match
        assertThat(set.match(ANY_AUDIO_TYPE, ANY_TYPE)).isEqualTo(HTML_UTF_8);

        // No matches
        assertThat(set.match(WEBM_VIDEO)).isNull();
    }

    @Test
    void matchHeaders() {
        final MediaTypeSet set = MediaTypeSet.of(HTML_UTF_8, PLAIN_TEXT_UTF_8);

        // No ranges
        assertThat(set.matchHeaders()).isNull();
        assertThat(set.matchHeaders("")).isNull();
        assertThat(set.matchHeaders(ImmutableList.of())).isNull();

        // More than one range
        assertThat(set.matchHeaders("text/html; q=0.5, text/plain")).isEqualTo(PLAIN_TEXT_UTF_8);
        assertThat(set.matchHeaders(ImmutableList.of("text/html, text/plain; q=0.5")))
                .isEqualTo(HTML_UTF_8);

        // Wildcard match
        assertThat(set.matchHeaders("audio/*, */*")).isEqualTo(HTML_UTF_8);

        // No matches
        assertThat(set.matchHeaders("video/webm")).isNull();
    }

    @Test
    void moreSpecificRangeWins() {
        final MediaType HTML_UTF_8_LEVEL_1 = HTML_UTF_8.withParameter("level", "1");
        final MediaTypeSet set = MediaTypeSet.of(WEBM_VIDEO,
                                                 PLAIN_TEXT_UTF_8,
                                                 HTML_UTF_8,
                                                 HTML_UTF_8_LEVEL_1);

        assertThat(set.matchHeaders("*/*")).isEqualTo(WEBM_VIDEO);
        assertThat(set.matchHeaders("*/*, text/*")).isEqualTo(PLAIN_TEXT_UTF_8);
        assertThat(set.matchHeaders("text/*, text/html")).isEqualTo(HTML_UTF_8);
        assertThat(set.matchHeaders("text/html, text/html; level=0")).isEqualTo(HTML_UTF_8);
        assertThat(set.matchHeaders("text/html, text/html; level=1")).isEqualTo(HTML_UTF_8_LEVEL_1);
    }

    @Test
    void invalidRange() {
        final MediaTypeSet set = MediaTypeSet.of(HTML_UTF_8);
        assertThat(set.matchHeaders("foo, */*")).isEqualTo(HTML_UTF_8);
    }

    @Test
    void invalidQValue() {
        final MediaTypeSet set = MediaTypeSet.of(HTML_UTF_8, PLAIN_TEXT_UTF_8);

        // A bad qvalue is interpreted as 0.
        assertThat(set.matchHeaders("text/*; q=bad, text/plain; q=0.5")).isEqualTo(PLAIN_TEXT_UTF_8);
    }

    @Test
    void parameterMatching() {
        final MediaType HTML_US_ASCII = HTML_UTF_8.withCharset(StandardCharsets.US_ASCII);
        final MediaTypeSet set = MediaTypeSet.of(HTML_UTF_8, HTML_US_ASCII);

        assertThat(set.matchHeaders("*/*")).isEqualTo(HTML_UTF_8);

        // Parameter requirement must be respected.
        assertThat(set.matchHeaders("*/*; charset=UTF-8")).isEqualTo(HTML_UTF_8);
        assertThat(set.matchHeaders("*/*; charset=US-ASCII")).isEqualTo(HTML_US_ASCII);

        // Case-insensitive comparison
        assertThat(set.matchHeaders("*/*; charset=utf-8")).isEqualTo(HTML_UTF_8);
        assertThat(set.matchHeaders("*/*; charset=us-ascii")).isEqualTo(HTML_US_ASCII);

        // Parameter requirements did not meet.
        assertThat(set.matchHeaders("*/*; charset=UTF-8; mode=foo")).isNull();
    }

    @Test
    void testAddRanges() {
        final List<MediaType> ranges = new ArrayList<>();

        // Single element without whitespaces
        MediaTypeSet.addRanges(ranges, "text/plain");
        assertThat(ranges).containsExactly(MediaType.parse("text/plain"));
        ranges.clear();

        // Multiple elements without whitespaces
        MediaTypeSet.addRanges(ranges, "text/plain,text/html");
        assertThat(ranges).containsExactly(MediaType.parse("text/plain"),
                                           MediaType.parse("text/html"));
        ranges.clear();

        // Single element with whitespaces
        MediaTypeSet.addRanges(ranges, " text/plain ");
        assertThat(ranges).containsExactly(MediaType.parse("text/plain"));
        ranges.clear();

        // Multiple elements with whitespaces
        MediaTypeSet.addRanges(ranges, " text/plain , text/html ");
        assertThat(ranges).containsExactly(MediaType.parse("text/plain"),
                                           MediaType.parse("text/html"));
        ranges.clear();

        // Quoted strings
        MediaTypeSet.addRanges(ranges, "text/plain; foo=\"b\\\"a,r\", text/html; bar=\"b\\a\\z\"");
        assertThat(ranges).containsExactly(MediaType.parse("text/plain; foo=\"b\\\"a,r\""),
                                           MediaType.parse("text/html; bar=baz"));
        ranges.clear();

        // Empty elements
        MediaTypeSet.addRanges(ranges, ",,,");
        assertThat(ranges).isEmpty();

        // Empty elements with whitespaces
        MediaTypeSet.addRanges(ranges, " , , , ");
        assertThat(ranges).isEmpty();
    }
}
