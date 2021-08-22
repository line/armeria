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
/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.stream.ByteBufDecoderInput;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;

class MimeParserTest {

    // Forked from https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/media/multipart/src/test/java/io/helidon/media/multipart/MimeParserTest.java

    @Test
    void testSkipPreambule() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary +
                               "          \t     \t  \t " +
                               "\r\n" +
                               "Content-Id: part1\n" +
                               '\n' +
                               "1\r\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse("boundary", chunk1);
        assertThat(parts).hasSize(1);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.contentUtf8()).isEqualTo("1");
    }

    @Test
    void testNoPreambule() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary +
                               "Content-Id: part1\n" +
                               '\n' +
                               "1\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse("boundary", chunk1);
        assertThat(parts).hasSize(1);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers().get('-' + boundary + "Content-Id")).isEqualTo("part1");
        assertThat(part1.contentUtf8()).isEqualTo("1");
    }

    @Test
    void testEndMessageEvent() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "1\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part2\n" +
                               '\n' +
                               "2\n" +
                               "--" + boundary + "--").getBytes();

        // ignore?
        // final MimeParser.ParserEvent lastEvent = parse(boundary, chunk1).lastEvent;
        // assertThat(lastEvent).isNotNull();
        // assertThat(lastEvent.type()).isEqualTo(MimeParser.EventType.END_MESSAGE);
    }

    @Test
    void testBoundaryWhiteSpace() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + "   \n" +
                               "Content-Id: part1\n" +
                               '\n' +
                               "1\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part2\n" +
                               '\n' +
                               "2\n" +
                               "--" + boundary + "--   ").getBytes();

        final List<AggregatedBodyPart> parts = parse("boundary", chunk1);
        assertThat(parts).hasSize(2);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.contentUtf8()).isEqualTo("1");

        final AggregatedBodyPart part2 = parts.get(1);
        assertThat(part2.headers().get("Content-Id")).isEqualTo("part2");
        assertThat(part2.contentUtf8()).isEqualTo("2");
    }

    @Test
    void testMsg() {
        final String boundary = "----=_Part_4_910054940.1065629194743";
        final byte[] chunk1 = concat(("--" + boundary + '\n' +
                                      "Content-Type: text/xml; charset=UTF-8\n" +
                                      "Content-Transfer-Encoding: binary\n" +
                                      "Content-Id: part1\n" +
                                      "Content-Description:   this is part1\n" +
                                      '\n' +
                                      "<foo>bar</foo>\n" +
                                      "--" + boundary + '\n' +
                                      "Content-Type: image/jpeg\n" +
                                      "Content-Transfer-Encoding: binary\n" +
                                      "Content-Id: part2\n" +
                                      '\n').getBytes(),
                                     new byte[] { (byte) 0xff, (byte) 0xd8 },
                                     ("\n--" + boundary + "--").getBytes());

        final List<AggregatedBodyPart> parts = parse(boundary, chunk1);
        assertThat(parts).hasSize(2);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers().get("Content-Type")).isEqualTo("text/xml; charset=UTF-8");
        assertThat(part1.headers().get("Content-Transfer-Encoding")).isEqualTo("binary");
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().get("Content-Description")).isEqualTo("this is part1");
        assertThat(part1.contentUtf8()).isEqualTo("<foo>bar</foo>");

        final AggregatedBodyPart part2 = parts.get(1);
        assertThat(part2.headers().get("Content-Type")).isEqualTo("image/jpeg");
        assertThat(part2.headers().get("Content-Transfer-Encoding")).isEqualTo("binary");
        assertThat(part2.headers().get("Content-Id")).isEqualTo("part2");
        final byte[] content = part2.content().array();
        assertThat(content[0]).isEqualTo((byte) 0xff);
        assertThat(content[1]).isEqualTo((byte) 0xd8);
    }

    @Test
    void testEmptyPart() {
        final String boundary = "----=_Part_7_10584188.1123489648993";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Type: text/xml; charset=utf-8\n" +
                               "Content-Id: part1\n" +
                               '\n' +
                               "--" + boundary + '\n' +
                               "Content-Type: text/xml\n" +
                               "Content-Id: part2\n" +
                               '\n' +
                               "<foo>bar</foo>\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, chunk1);
        assertThat(parts).hasSize(2);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers().get("Content-Type")).isEqualTo("text/xml; charset=utf-8");
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.contentUtf8().length()).isEqualTo(0);

        final AggregatedBodyPart part2 = parts.get(1);
        assertThat(part2.headers().get("Content-Type")).isEqualTo("text/xml");
        assertThat(part2.headers().get("Content-Id")).isEqualTo("part2");
        assertThat(part2.contentUtf8()).isEqualTo("<foo>bar</foo>");
    }

    @Test
    void testNoHeaders() {
        final String boundary = "----=_Part_7_10584188.1123489648993";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               '\n' +
                               "<foo>bar</foo>\n" +
                               "--" + boundary + '\n' +
                               '\n' +
                               "<bar>foo</bar>\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, chunk1);
        assertThat(parts).hasSize(2);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(1);

        assertThat(part1.contentUtf8()).isEqualTo("<foo>bar</foo>");

        final AggregatedBodyPart part2 = parts.get(1);
        assertThat(part2.headers()).hasSize(1);
        assertThat(part2.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part2.contentUtf8()).isEqualTo("<bar>foo</bar>");
    }

    @Test
    void testNoClosingBoundary() {
        final String boundary = "----=_Part_4_910054940.1065629194743";
        final byte[] chunk1 = concat(("--" + boundary + '\n' +
                                      "Content-Type: text/xml; charset=UTF-8\n" +
                                      "Content-Id: part1\n" +
                                      '\n' +
                                      "<foo>bar</foo>\n" +
                                      "--" + boundary + '\n' +
                                      "Content-Type: image/jpeg\n" +
                                      "Content-Transfer-Encoding: binary\n" +
                                      "Content-Id: part2\n" +
                                      '\n').getBytes(),
                                     new byte[] { (byte) 0xff, (byte) 0xd8 });

        assertThatThrownBy(() -> parse(boundary, chunk1))
                .isInstanceOf(MimeParsingException.class)
                .hasMessageContaining("No closing MIME boundary");
    }

    @Test
    void testIntermediateBoundary() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + "   \n" +
                               "Content-Id: part1\n" +
                               '\n' +
                               "1\n" +
                               "--" + boundary + " \r\n" +
                               "Content-Id: part2\n" +
                               '\n' +
                               "2\n" +
                               "--" + boundary + "--   ").getBytes();

        final List<AggregatedBodyPart> parts = parse("boundary", chunk1);
        assertThat(parts).hasSize(2);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.contentUtf8()).isEqualTo("1");

        final AggregatedBodyPart part2 = parts.get(1);
        assertThat(part2.headers().get("Content-Id")).isEqualTo("part2");
        assertThat(part2.contentUtf8()).isEqualTo("2");
    }

    @Test
    void testBoundaryInBody() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "1 --" + boundary + " in body\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part2\n" +
                               '\n' +
                               "2 --" + boundary + " in body\n" +
                               "--" + boundary + " starts on a new line\n" +
                               "--" + boundary + "--         ").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, chunk1);
        assertThat(parts).hasSize(2);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("1 --" + boundary + " in body");

        final AggregatedBodyPart part2 = parts.get(1);
        assertThat(part2.headers()).hasSize(2);
        assertThat(part2.headers().get("Content-Id")).isEqualTo("part2");
        assertThat(part2.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part2.contentUtf8()).isEqualTo("2 --" + boundary + " in body\n--" +
                                                  boundary + " starts on a new line");
    }

    @Test
    void testInvalidClosingBoundary() {
        final String boundary = "----=_Part_4_910054940.1065629194743";
        final byte[] chunk1 = concat(("--" + boundary + '\n' +
                                      "Content-Type: text/xml; charset=UTF-8\n" +
                                      "Content-Id: part1\n" +
                                      '\n' +
                                      "<foo>bar</foo>\n" +
                                      "--" + boundary + '\n' +
                                      "Content-Type: image/jpeg\n" +
                                      "Content-Transfer-Encoding: binary\n" +
                                      "Content-Id: part2\n" +
                                      '\n').getBytes(),
                                     new byte[] { (byte) 0xff, (byte) 0xd8 },
                                     ("\n--" + boundary).getBytes());

        assertThatThrownBy(() -> parse(boundary, chunk1))
                .isInstanceOf(MimeParsingException.class)
                .hasMessageContaining("No closing MIME boundary");
    }

    @Test
    void testOneExactPartPerChunk() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "body 1\n" +
                               "--" + boundary + '\n').getBytes();
        final byte[] chunk2 = ("Content-Id: part2\n" +
                               '\n' +
                               "body 2\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2));
        assertThat(parts).hasSize(2);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("body 1");

        final AggregatedBodyPart part2 = parts.get(1);
        assertThat(part2.headers()).hasSize(2);
        assertThat(part2.headers().get("Content-Id")).isEqualTo("part2");
        assertThat(part2.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part2.contentUtf8()).isEqualTo("body 2");
    }

    @Test
    void testPartInMultipleChunks() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "this-is-the-1st-slice-of-the-body\n").getBytes();
        final byte[] chunk2 = ("this-is-the-2nd-slice-of-the-body\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2));
        assertThat(parts).hasSize(1);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);

        assertThat(part1.contentUtf8()).isEqualTo("this-is-the-1st-slice-of-the-body\n" +
                                                  "this-is-the-2nd-slice-of-the-body");
    }

    @Test
    void testBoundaryAcrossChunks() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "this-is-the-body-of-part1\n" +
                               "--" + boundary.substring(0, 3)).getBytes();
        final byte[] chunk2 = (boundary.substring(3) + '\n' +
                               '\n' +
                               "this-is-the-body-of-part2\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2));
        assertThat(parts).hasSize(2);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("this-is-the-body-of-part1");

        final AggregatedBodyPart part2 = parts.get(1);
        assertThat(part2.headers()).hasSize(1);
        assertThat(part2.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part2.contentUtf8()).isEqualTo("this-is-the-body-of-part2");
    }

    @Test
    void testClosingBoundaryAcrossChunks() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "this-is-the-body-of-part1\n" +
                               "--" + boundary.substring(0, 3)).getBytes();
        final byte[] chunk2 = (boundary.substring(3) + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2));
        assertThat(parts).hasSize(1);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("this-is-the-body-of-part1");
    }

    @Test
    void testPreamble() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("\n\n\n\r\r\r\n\n\n\n\r\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, chunk1);
        assertThat(parts).hasSize(1);
        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("part1");
    }

    @Test
    void testPreambleWithNoStartingBoundary() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("       \t   \t\t      \t \r\n" +
                               "Content-Id: part1\n" +
                               '\n' +
                               "part1\n").getBytes();

        assertThatThrownBy(() -> parse(boundary, chunk1))
                .isInstanceOf(MimeParsingException.class)
                .hasMessageContaining("Missing start boundary");
    }

    @Test
    void testPreambleWithStartingBoundaryInNextChunk() {
        final String boundary = "boundary";
        final byte[] chunk1 = "      \t    \t    \r\n".getBytes();
        final byte[] chunk2 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2));
        assertThat(parts).hasSize(1);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("part1");
    }

    @Test
    void testPreambleAcrossChunks() {
        final String boundary = "boundary";
        final byte[] chunk1 = "      \t    \t    ".getBytes();
        final byte[] chunk2 = ("\t      \t     \r\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();

        final List<AggregatedBodyPart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2));
        assertThat(parts).hasSize(1);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("part1");
    }

    @Test
    void testPreambleAcrossChunksWithNoStartingBoundary() {
        final String boundary = "boundary";
        final byte[] chunk1 = "      \t    \t    ".getBytes();
        final byte[] chunk2 = ("\t      \t     \r\n" +
                               "Content-Id: part1\n" +
                               '\n' +
                               "part1\n").getBytes();
        assertThatThrownBy(() -> parse(boundary, ImmutableList.of(chunk1, chunk2)))
                .isInstanceOf(MimeParsingException.class)
                .hasMessageContaining("Missing start boundary");
    }

    @Test
    void testHeadersWithNoBlankLine() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();
        assertThatThrownBy(() -> parse(boundary, chunk1))
                .isInstanceOf(MimeParsingException.class)
                .hasMessageContaining("Invalid header line: part1");
    }

    @Test
    void testHeadersAcrossChunksWithNoBlankLine() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               "Content-Type: text/plain\n").getBytes();
        final byte[] chunk2 = ("Content-Description: this is part1\n" +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();
        assertThatThrownBy(() -> parse(boundary, ImmutableList.of(chunk1, chunk2)))
                .isInstanceOf(MimeParsingException.class)
                .hasMessageContaining("Invalid header line: part1");
    }

    @Test
    void testHeadersAcrossChunks() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n").getBytes();
        final byte[] chunk2 = ("Content-Type: text/plain\n" +
                               '\n' +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();
        final List<AggregatedBodyPart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2));
        assertThat(parts).hasSize(1);
        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("part1");
    }

    @Test
    void testHeaderBlankLineInNextChunk() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               "Content-Type: text/plain\r\n").getBytes();
        final byte[] chunk2 = ('\n' +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();
        final List<AggregatedBodyPart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2));
        assertThat(parts).hasSize(1);
        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("part1");
    }

    @Test
    void testHeaderValueWithLeadingWhiteSpace() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: \tpart1\n" +
                               "Content-Type:    \t  \t\t   text/plain\r\n" +
                               '\n' +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();
        final List<AggregatedBodyPart> parts = parse(boundary, chunk1);
        assertThat(parts).hasSize(1);
        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("part1");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("part1");
    }

    @Test
    void testHeaderValueWithWhiteSpacesOnly() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id:    \t  \t\t \n" +
                               '\n' +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();
        final List<AggregatedBodyPart> parts = parse(boundary, chunk1);
        assertThat(parts).hasSize(1);

        final AggregatedBodyPart part1 = parts.get(0);
        assertThat(part1.headers()).hasSize(2);
        assertThat(part1.headers().get("Content-Id")).isEqualTo("");
        assertThat(part1.headers().contentType()).isEqualTo(MediaType.PLAIN_TEXT);
        assertThat(part1.contentUtf8()).isEqualTo("part1");
    }

    @Test
    void testParserClosed() {
        assertThatThrownBy(() -> {
            final MimeParser parser = new MimeParser(null, null, "boundary", null);
            parser.close();
            parser.parse();
        }).isInstanceOf(MimeParsingException.class)
          .hasMessageContaining("Parser is closed");
    }

    /**
     * Concatenate the specified byte arrays.
     *
     * @param arrays byte arrays to concatenate
     * @return resulting array of the concatenation
     */
    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        final byte[] res = new byte[length];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, res, pos, array.length);
            pos += array.length;
        }
        return res;
    }

    /**
     * Parse the parts in the given chunk.
     *
     * @param boundary boundary string
     * @param data for the chunks to parse
     * @return test parser event processor
     */
    private static List<AggregatedBodyPart> parse(String boundary, byte[] data) {
        return parse(boundary, ImmutableList.of(data));
    }

    /**
     * Parse the parts in the given chunks.
     *
     * @param boundary boundary string
     * @param data for the chunks to parse
     * @return test parser event processor
     */
    private static List<AggregatedBodyPart> parse(String boundary, List<byte[]> data) {
        final ByteBufDecoderInput input = new ByteBufDecoderInput(ByteBufAllocator.DEFAULT);
        final List<BodyPart> output = new ArrayList<>();
        final MimeParser parser = new MimeParser(input, output::add, boundary, ignored -> {
        });
        for (byte[] bytes : data) {
            input.add(Unpooled.wrappedBuffer(bytes));
            parser.parse();
        }
        parser.close();
        return output.stream()
                     .map(part -> {
                         final HttpData content = Flux.from(part.content())
                                                      .map(HttpData::array)
                                                      .reduce(Bytes::concat)
                                                      .map(HttpData::wrap)
                                                      .block();
                         return AggregatedBodyPart.of(part.headers(), content);
                     }).collect(toImmutableList());
    }
}
