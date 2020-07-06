/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

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
                               "1\n" +
                               "--" + boundary + "--").getBytes();

        final List<MimePart> parts = parse("boundary", chunk1).parts;
        assertThat(parts.size()).isEqualTo(1);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("1");
    }

    @Test
    void testNoPreambule() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary +
                               "Content-Id: part1\n" +
                               '\n' +
                               "1\n" +
                               "--" + boundary + "--").getBytes();

        final List<MimePart> parts = parse("boundary", chunk1).parts;
        assertThat(parts.size()).isEqualTo(1);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.get('-' + boundary + "Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("1");
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

        final MimeParser.ParserEvent lastEvent = parse(boundary, chunk1).lastEvent;
        assertThat(lastEvent).isNotNull();
        assertThat(lastEvent.type()).isEqualTo(MimeParser.EventType.END_MESSAGE);
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

        final List<MimePart> parts = parse("boundary", chunk1).parts;
        assertThat(parts.size()).isEqualTo(2);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("1");

        final MimePart part2 = parts.get(1);
        assertThat(part2.headers.get("Content-Id")).containsExactly("part2");
        assertThat(part2.content).isNotNull();
        assertThat(new String(part2.content)).isEqualTo("2");
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
                                     new byte[]{(byte) 0xff, (byte) 0xd8},
                                     ("\n--" + boundary + "--").getBytes());

        final List<MimePart> parts = parse(boundary, chunk1).parts;
        assertThat(parts.size()).isEqualTo(2);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.get("Content-Type")).containsExactly("text/xml; charset=UTF-8");
        assertThat(part1.headers.get("Content-Transfer-Encoding")).containsExactly("binary");
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.headers.get("Content-Description")).containsExactly("this is part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("<foo>bar</foo>");

        final MimePart part2 = parts.get(1);
        assertThat(part2.headers.get("Content-Type")).containsExactly("image/jpeg");
        assertThat(part2.headers.get("Content-Transfer-Encoding")).containsExactly("binary");
        assertThat(part2.headers.get("Content-Id")).containsExactly("part2");
        assertThat(part2.content).isNotNull();
        assertThat(part2.content[0]).isEqualTo((byte) 0xff);
        assertThat(part2.content[1]).isEqualTo((byte) 0xd8);
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

        final List<MimePart> parts = parse(boundary, chunk1).parts;
        assertThat(parts.size()).isEqualTo(2);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.get("Content-Type")).containsExactly("text/xml; charset=utf-8");
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(part1.content.length).isEqualTo(0);

        final MimePart part2 = parts.get(1);
        assertThat(part2.headers.get("Content-Type")).containsExactly("text/xml");
        assertThat(part2.headers.get("Content-Id")).containsExactly("part2");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part2.content)).isEqualTo("<foo>bar</foo>");
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

        final List<MimePart> parts = parse(boundary, chunk1).parts;
        assertThat(parts.size()).isEqualTo(2);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(0);
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("<foo>bar</foo>");

        final MimePart part2 = parts.get(1);
        assertThat(part2.headers.size()).isEqualTo(0);
        assertThat(part2.content).isNotNull();
        assertThat(new String(part2.content)).isEqualTo("<bar>foo</bar>");
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
                                     new byte[]{(byte) 0xff, (byte) 0xd8});

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

        final List<MimePart> parts = parse("boundary", chunk1).parts;
        assertThat(parts.size()).isEqualTo(2);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("1");

        final MimePart part2 = parts.get(1);
        assertThat(part2.headers.get("Content-Id")).containsExactly("part2");
        assertThat(part2.content).isNotNull();
        assertThat(new String(part2.content)).isEqualTo("2");
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

        final List<MimePart> parts = parse(boundary, chunk1).parts;
        assertThat(parts.size()).isEqualTo(2);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(1);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("1 --" + boundary + " in body");

        final MimePart part2 = parts.get(1);
        assertThat(part2.headers.size()).isEqualTo(1);
        assertThat(part2.headers.get("Content-Id")).containsExactly("part2");
        assertThat(part2.content).isNotNull();
        assertThat(new String(part2.content)).isEqualTo("2 --" + boundary + " in body\n--" +
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
                                     new byte[]{(byte) 0xff, (byte) 0xd8},
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

        final List<MimePart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2)).parts;
        assertThat(parts.size()).isEqualTo(2);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(1);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("body 1");

        final MimePart part2 = parts.get(1);
        assertThat(part2.headers.size()).isEqualTo(1);
        assertThat(part2.headers.get("Content-Id")).containsExactly("part2");
        assertThat(part2.content).isNotNull();
        assertThat(new String(part2.content)).isEqualTo("body 2");
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

        final List<MimePart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2)).parts;
        assertThat(parts.size()).isEqualTo(1);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(1);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("this-is-the-1st-slice-of-the-body\n" +
                                                        "this-is-the-2nd-slice-of-the-body");
    }

    @Test
    void testBoundaryAcrossChunksDataRequired() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "this-is-the-body-of-part1\n" +
                               "--" + boundary.substring(0, 3)).getBytes();

        final ParserEventProcessor processor = new ParserEventProcessor();
        final MimeParser parser = new MimeParser(boundary, processor);
        parser.offer(Unpooled.wrappedBuffer(chunk1));
        parser.parse();

        assertThat(processor.partContent).isNotNull();
        assertThat(new String(processor.partContent)).isEqualTo("this-is-the-body-of-");
        assertThat(processor.lastEvent).isNotNull();
        assertThat(processor.lastEvent.type()).isEqualTo(MimeParser.EventType.DATA_REQUIRED);
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

        final List<MimePart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2)).parts;
        assertThat(parts.size()).isEqualTo(2);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(1);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("this-is-the-body-of-part1");

        final MimePart part2 = parts.get(1);
        assertThat(part2.headers.size()).isEqualTo(0);
        assertThat(part2.content).isNotNull();
        assertThat(new String(part2.content)).isEqualTo("this-is-the-body-of-part2");
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

        final List<MimePart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2)).parts;
        assertThat(parts.size()).isEqualTo(1);

        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(1);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("this-is-the-body-of-part1");
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

        final List<MimePart> parts = parse(boundary, chunk1).parts;
        assertThat(parts.size()).isEqualTo(1);
        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(1);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("part1");
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

        final List<MimePart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2)).parts;
        assertThat(parts.size()).isEqualTo(1);
        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(1);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("part1");
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

        final List<MimePart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2)).parts;
        assertThat(parts.size()).isEqualTo(1);
        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(1);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("part1");
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
                .hasMessageContaining("No blank line found");
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
                .hasMessageContaining("No blank line found");
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
        final List<MimePart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2)).parts;
        assertThat(parts.size()).isEqualTo(1);
        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(2);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.headers.get("Content-Type")).containsExactly("text/plain");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("part1");
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
        final List<MimePart> parts = parse(boundary, ImmutableList.of(chunk1, chunk2)).parts;
        assertThat(parts.size()).isEqualTo(1);
        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(2);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.headers.get("Content-Type")).containsExactly("text/plain");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("part1");
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
        final List<MimePart> parts = parse(boundary, chunk1).parts;
        assertThat(parts.size()).isEqualTo(1);
        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(2);
        assertThat(part1.headers.get("Content-Id")).containsExactly("part1");
        assertThat(part1.headers.get("Content-Type")).containsExactly("text/plain");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("part1");
    }

    @Test
    void testHeaderValueWithWhiteSpacesOnly() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Type:    \t  \t\t \n" +
                               '\n' +
                               "part1\n" +
                               "--" + boundary + "--").getBytes();
        final List<MimePart> parts = parse(boundary, chunk1).parts;
        assertThat(parts.size()).isEqualTo(1);
        final MimePart part1 = parts.get(0);
        assertThat(part1.headers.size()).isEqualTo(1);
        assertThat(part1.headers.get("Content-Type")).containsExactly("");
        assertThat(part1.content).isNotNull();
        assertThat(new String(part1.content)).isEqualTo("part1");
    }

    @Test
    void testParserClosed() {
        assertThatThrownBy(() -> {
            final ParserEventProcessor processor = new ParserEventProcessor();
            final MimeParser parser = new MimeParser("boundary", processor);
            parser.close();
            parser.offer(Unpooled.wrappedBuffer("foo".getBytes()));
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
    static ParserEventProcessor parse(String boundary, byte[] data) {
        return parse(boundary, ImmutableList.of(data));
    }

    /**
     * Parse the parts in the given chunks.
     *
     * @param boundary boundary string
     * @param data for the chunks to parse
     * @return test parser event processor
     */
    static ParserEventProcessor parse(String boundary, List<byte[]> data) {
        final ParserEventProcessor processor = new ParserEventProcessor();
        final MimeParser parser = new MimeParser(boundary, processor);
        for (byte[] bytes : data) {
            parser.offer(Unpooled.wrappedBuffer(bytes));
            parser.parse();
        }
        parser.close();
        return processor;
    }

    /**
     * Test parser event processor.
     */
    static final class ParserEventProcessor implements MimeParser.EventProcessor {

        List<MimePart> parts = new LinkedList<>();
        Map<String, List<String>> partHeaders = new HashMap<>();
        byte[] partContent;
        MimeParser.ParserEvent lastEvent;

        @Override
        public void process(MimeParser.ParserEvent event) {
            switch (event.type()) {
                case START_PART:
                    partHeaders = new HashMap<>();
                    partContent = null;
                    break;

                case HEADER:
                    final MimeParser.HeaderEvent headerEvent = event.asHeaderEvent();
                    final String name = headerEvent.name();
                    final String value = headerEvent.value();
                    assertThat(name).isNotNull();
                    assertThat(name.length()).isNotEqualTo(0);
                    assertThat(value).isNotNull();
                    final List<String> values = partHeaders.computeIfAbsent(name, k -> new ArrayList<>());
                    values.add(value);
                    break;

                case CONTENT:
                    final ByteBuf content = event.asContentEvent().content();
                    assertThat(content).isNotNull();
                    final byte[] contentBytes = new byte[content.capacity()];
                    content.readBytes(contentBytes);
                    if (partContent == null) {
                        partContent = contentBytes;
                    } else {
                        partContent = concat(partContent, contentBytes);
                    }
                    break;

                case END_PART:
                    parts.add(new MimePart(partHeaders, partContent));
                    break;
            }
            lastEvent = event;
        }
    }

    /**
     * Pair of part headers and body part content.
     */
    static final class MimePart {

        final Map<String, List<String>> headers;
        final byte[] content;

        MimePart(Map<String, List<String>> headers, byte[] content) {
            this.headers = headers;
            this.content = content;
        }
    }
}
