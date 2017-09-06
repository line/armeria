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

package com.linecorp.armeria.internal.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class StatusMessageEscaperTest {

    @Test
    public void metadataEncode_lowAscii() {
        String s = StatusMessageEscaper.escape("my favorite character is \u0000");
        assertThat(s).isEqualTo("my favorite character is %00");
    }

    @Test
    public void metadataEncode_percent() {
        String s = StatusMessageEscaper.escape("my favorite character is %");
        assertThat(s).isEqualTo("my favorite character is %25");
    }

    @Test
    public void metadataEncode_surrogatePair() {
        String s = StatusMessageEscaper.escape("my favorite character is 𐀁");
        assertThat(s).isEqualTo("my favorite character is %F0%90%80%81");
    }

    @Test
    public void metadataEncode_unmatchedHighSurrogate() {
        String s = StatusMessageEscaper.escape("my favorite character is " + ((char) 0xD801));
        assertThat(s).isEqualTo("my favorite character is ?");
    }

    @Test
    public void metadataEncode_unmatchedLowSurrogate() {
        String s = StatusMessageEscaper.escape("my favorite character is " + ((char) 0xDC37));
        assertThat(s).isEqualTo("my favorite character is ?");
    }

    @Test
    public void metadataEncode_maxSurrogatePair() {
        String s = StatusMessageEscaper.escape(
                "my favorite character is " + ((char) 0xDBFF) + ((char) 0xDFFF));
        assertThat(s).isEqualTo("my favorite character is %F4%8F%BF%BF");
    }

    @Test
    public void metadataDecode_ascii() {
        String s = StatusMessageEscaper.unescape("Hello");
        assertThat(s).isEqualTo("Hello");
    }

    @Test
    public void metadataDecode_percent() {
        String s = StatusMessageEscaper.unescape("H%61o");
        assertThat(s).isEqualTo("Hao");
    }

    @Test
    public void metadataDecode_percentUnderflow() {
        String s = StatusMessageEscaper.unescape("H%6");
        assertThat(s).isEqualTo("H%6");
    }

    @Test
    public void metadataDecode_surrogate() {
        String s = StatusMessageEscaper.unescape("%F0%90%80%81");
        assertThat(s).isEqualTo("𐀁");
    }

    @Test
    public void metadataDecode_badEncoding() {
        String s = StatusMessageEscaper.unescape("%G0");
        assertThat(s).isEqualTo("%G0");
    }
}
