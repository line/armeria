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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.multipart.MultipartDecoder.BodyPartPublisher;
import com.linecorp.armeria.common.stream.StreamDecoderInput;
import com.linecorp.armeria.common.stream.StreamDecoderOutput;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * Parser for multipart MIME message.
 */
final class MimeParser {

    // Forked from https://github.com/oracle/helidon/blob/a9363a3d226a3154e2fb99abe230239758504436/media/multipart/src/main/java/io/helidon/media/multipart/MimeParser.java

    private static final Logger logger = LoggerFactory.getLogger(MimeParser.class);

    private static final ByteBuf NEED_MORE = Unpooled.buffer(1);
    private static final Charset HEADER_ENCODING = StandardCharsets.ISO_8859_1;

    /**
     * Boundary as bytes.
     */
    private final byte[] boundaryBytes;

    private final MultipartDecoder multipartDecoder;

    /**
     * Boundary length.
     */
    private final int boundaryLength;

    /**
     * BnM algorithm: Bad Character Shift table.
     */
    private final int[] badCharacters = new int[128];

    /**
     * BnM algorithm: Good Suffix Shift table.
     */
    private final int[] goodSuffixes;

    /**
     * The current parser state.
     */
    private State state = State.START_MESSAGE;

    /**
     * The input of multipart data.
     */
    private final StreamDecoderInput in;

    /**
     * The output which the parsed {@link BodyPart}s are added to.
     */
    private final StreamDecoderOutput<BodyPart> out;

    /**
     * The builder for the headers of a body part.
     */
    @Nullable
    private HttpHeadersBuilder bodyPartHeadersBuilder;

    /**
     * The builder for a body part.
     */
    @Nullable
    private BodyPartBuilder bodyPartBuilder;

    /**
     * The publisher that emits body part contents.
     */
    private MultipartDecoder.@Nullable BodyPartPublisher bodyPartPublisher;

    /**
     * Read and process body parts until we see the terminating boundary line.
     */
    private boolean done;

    /**
     * Beginning of the line.
     */
    private boolean startOfLine;

    /**
     * The position of the next boundary.
     */
    private int boundaryStart;

    /**
     * Indicates whether this parser is closed.
     */
    private boolean closed;

    /**
     * Parses the MIME content.
     */
    MimeParser(StreamDecoderInput in, StreamDecoderOutput<BodyPart> out, String boundary,
               MultipartDecoder multipartDecoder) {
        this.in = in;
        this.out = out;
        boundaryBytes = getBytes("--" + boundary);
        this.multipartDecoder = multipartDecoder;
        boundaryLength = boundaryBytes.length;
        goodSuffixes = new int[boundaryLength];
        compileBoundaryPattern();
    }

    /**
     * Marks this parser instance as closed. Invoking this method indicates that
     * no more data will be pushed to the parsing buffer.
     *
     * @throws MimeParsingException if the parser state is not {@code END_MESSAGE} or {@code START_MESSAGE}
     */
    void close() {
        if (closed) {
            return;
        }

        switch (state) {
            case START_MESSAGE:
            case END_MESSAGE:
                closed = true;
                break;
            case SKIP_PREAMBLE:
                throw new MimeParsingException("Missing start boundary");
            case BODY:
                throw new MimeParsingException("No closing MIME boundary");
            case HEADERS:
                throw new MimeParsingException("No blank line found");
            default:
                throw new MimeParsingException("Invalid state: " + state);
        }
    }

    /**
     * Advances parsing.
     * @throws MimeParsingException if an error occurs during parsing
     */
    void parse() {
        if (closed) {
            throw new MimeParsingException("Parser is closed");
        }

        try {
            while (true) {
                switch (state) {
                    case START_MESSAGE:
                        logger.trace("state={}", State.START_MESSAGE);
                        state = State.SKIP_PREAMBLE;
                        break;

                    case SKIP_PREAMBLE:
                        logger.trace("state={}", State.SKIP_PREAMBLE);
                        skipPreamble();
                        if (boundaryStart == -1) {
                            // Need more data; DecodedHttpStreamMessage will handle.
                            return;
                        }
                        logger.trace("Skipped the preamble.");
                        state = State.START_PART;
                        break;

                    case START_PART:
                        logger.trace("state={}", State.START_PART);
                        bodyPartHeadersBuilder = HttpHeaders.builder();
                        bodyPartBuilder = BodyPart.builder();
                        state = State.HEADERS;
                        break;

                    case HEADERS:
                        logger.trace("state={}", State.HEADERS);
                        final String headerLine = readHeaderLine();
                        if (headerLine == null) {
                            // Need more data; DecodedHttpStreamMessage will handle.
                            return;
                        }
                        if (!headerLine.isEmpty()) {
                            final int index = headerLine.indexOf(':');
                            if (index < 0) {
                                throw new MimeParsingException("Invalid header line: " + headerLine);
                            }
                            final String key = headerLine.substring(0, index).trim();
                            // Skip ':' from value
                            final String value = headerLine.substring(index + 1).trim();
                            bodyPartHeadersBuilder.add(key, value);
                            break;
                        }
                        state = State.BODY;
                        startOfLine = true;

                        bodyPartPublisher = multipartDecoder.onBodyPartBegin();
                        final BodyPart bodyPart = bodyPartBuilder.headers(bodyPartHeadersBuilder.build())
                                                                 .content(bodyPartPublisher)
                                                                 .build();
                        out.add(bodyPart);
                        break;

                    case BODY:
                        logger.trace("state={}", State.BODY);
                        final ByteBuf bodyContent = readBody();
                        if (bodyContent == NEED_MORE) {
                            final BodyPartPublisher currentPublisher = bodyPartPublisher;
                            currentPublisher.whenConsumed().thenRun(() -> {
                                if (currentPublisher.demand() > 0 && !currentPublisher.isComplete()) {
                                    multipartDecoder.requestUpstreamForBodyPartData();
                                }
                            });
                            return;
                        }
                        if (boundaryStart != -1) {
                            startOfLine = false;
                        }
                        // Use tryWrite() to avoid throwing exception.
                        // For example, when body part is cancelled, MimeParser need to ignore it without
                        // throwing exception.
                        bodyPartPublisher.tryWrite(HttpData.wrap(bodyContent));
                        break;

                    case END_PART:
                        logger.trace("state={}", State.END_PART);
                        if (done) {
                            state = State.END_MESSAGE;
                        } else {
                            state = State.START_PART;
                        }
                        bodyPartPublisher.close();
                        bodyPartPublisher = null;
                        bodyPartHeadersBuilder = null;
                        bodyPartBuilder = null;
                        break;

                    case END_MESSAGE:
                        logger.trace("state={}", State.END_MESSAGE);
                        return;

                    default:
                        // nothing to do
                }
            }
        } catch (MimeParsingException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new MimeParsingException(ex);
        }
    }

    /**
     * Reads the next part body content.
     * Returns {@link #NEED_MORE} if more data is required and no body content can be returned.
     */
    private ByteBuf readBody() {
        // matches boundary
        boundaryStart = match();
        final int length = in.readableBytes();

        if (boundaryStart == -1) {
            // No boundary is found
            if (boundaryLength + 1 < length) {
                // There may be an incomplete boundary at the end of the buffer.
                // Return the remaining data minus the boundary length
                // so that it can be processed next iteration.
                // e.g. |---body---|--bound|

                final int bodyLength = length - (boundaryLength + 1);
                return in.readBytes(bodyLength);
            }
            // remaining data can be a complete boundary, force it to be
            // processed during next iteration
            return NEED_MORE;
        }

        // Found boundary.
        // Is it at the start of a line ?
        int bodyLength = boundaryStart;
        if (startOfLine && bodyLength == 0) {
            // an empty body, nothing to do
            // e.g. ||--boundary|
        } else {
            final byte last = in.getByte(boundaryStart - 1);
            // Remove CRLF from bodyLength
            if (boundaryStart > 0 && (last == '\n' || last == '\r')) {
                // e.g. |---body---\n|--boundary|
                --bodyLength;
                if (last == '\n' && boundaryStart > 1 && in.getByte(boundaryStart - 2) == '\r') {
                    // e.g. |---body---\r\n|--boundary|
                    --bodyLength;
                }
            } else {
                // Boundary is not at beginning of a line. A boundary string can be in a body.
                // e.g. |---body---boundary---|
                return in.readBytes(bodyLength + 1);
            }
        }

        int boundaryEnd = boundaryStart + boundaryLength;
        // check if this is a "closing" boundary
        // e.g. |---body---\n|--boundary--|
        if (boundaryEnd + 1 < length && in.getByte(boundaryEnd) == '-' && in.getByte(boundaryEnd + 1) == '-') {

            state = State.END_PART;
            done = true;
            final ByteBuf body = safeReadBytes(in, bodyLength);

            // Discard a closing boundary
            in.skipBytes(boundaryLength + 2);
            return body;
        }

        // Consider all the linear whitespace in boundary+whitespace+"\r\n"
        // e.g. |---body---\n|--boundary \t\t\r\n|
        for (int i = boundaryEnd; i < length; i++) {
            final byte current = in.getByte(i);
            if (current == ' ' || current == '\t') {
                boundaryEnd++;
            } else {
                break;
            }
        }

        if (boundaryEnd < length) {
            // Check boundary+whitespace+"\n"
            // e.g. |---body---\n|--boundary\n|
            final byte closingChar = in.getByte(boundaryEnd);
            if (closingChar == '\n') {
                state = State.END_PART;
                final ByteBuf body = safeReadBytes(in, bodyLength);

                // Skip boundary+whitespace+"\n"
                in.skipBytes(boundaryEnd + 1 - bodyLength);
                return body;
            }

            // Check for boundary+whitespace+"\r\n"
            // e.g. |---body---\n|--boundary--\r\n|
            if (boundaryEnd + 1 < length &&
                closingChar == '\r' &&
                in.getByte(boundaryEnd + 1) == '\n') {

                state = State.END_PART;
                final ByteBuf body = safeReadBytes(in, bodyLength);

                // Skip boundary+whitespace+"\r\n"
                in.skipBytes(boundaryEnd + 2 - bodyLength);
                return body;
            }
        }

        if (boundaryEnd + 1 < length) {
            // It is not a closing boundary, but there is no CRLF.
            // A boundary string is in a part data.
            return in.readBytes(bodyLength + 1);
        }

        // A boundary is found but it's not a "closing" boundary
        // return everything before that boundary as the "closing" characters
        // might be available next iteration
        final ByteBuf body = safeReadBytes(in, bodyLength);
        in.skipBytes(boundaryStart - bodyLength);
        return body;
    }

    private static ByteBuf safeReadBytes(StreamDecoderInput in, int length) {
        if (length == 0) {
            return Unpooled.EMPTY_BUFFER;
        } else {
            return in.readBytes(length);
        }
    }

    /**
     * Skips the preamble.
     */
    private void skipPreamble() {
        boundaryStart = -1;
        final int boundaryStartOffset = match();
        if (boundaryStartOffset == -1) {
            // No boundary is found
            return;
        }

        final int length = in.readableBytes();

        // Three valid cases:
        // boundary+"--" + "whatever after --" // closing boundary
        // boundary+whitespace+"\n"
        // boundary+whitespace+"\r\n"

        // Check the closing boundary first.
        int followingCharOffset = boundaryStartOffset + boundaryLength;
        if (followingCharOffset == length) {
            // Need more data.
            return;
        }

        if (in.getByte(followingCharOffset) == '-') {
            if (followingCharOffset + 1 == length) {
                // Need more data.
                return;
            }
            if (in.getByte(followingCharOffset + 1) == '-') {
                in.skipBytes(followingCharOffset + 2);
                done = true;
                state = State.END_MESSAGE;
                return;
            }
            throwInvalidBoundaryException(followingCharOffset + 2);
        }

        // Consider all the whitespace. e.g. boundary+whitespace+"\r\n"
        int linearWhiteSpace = 0;
        for (int i = boundaryStartOffset + boundaryLength;
             i < length && (in.getByte(i) == ' ' || in.getByte(i) == '\t'); i++) {
            ++linearWhiteSpace;
        }

        // Check the rest.
        followingCharOffset = boundaryStartOffset + boundaryLength + linearWhiteSpace;
        if (followingCharOffset == length) {
            // Need more data.
            return;
        }

        final byte followingChar = in.getByte(followingCharOffset);
        if (followingChar == '\n') {
            in.skipBytes(followingCharOffset + 1);
            boundaryStart = boundaryStartOffset;
            return;
        }

        if (followingChar == '\r') {
            if (followingCharOffset + 1 == length) {
                // Need one more character.
                return;
            }

            if (in.getByte(followingCharOffset + 1) == '\n') {
                in.skipBytes(followingCharOffset + 2);
                boundaryStart = boundaryStartOffset;
                return;
            }
            throwInvalidBoundaryException(followingCharOffset + 2);
        }

        throwInvalidBoundaryException(followingCharOffset + 1);
    }

    private void throwInvalidBoundaryException(int length) {
        final ByteBuf byteBuf = in.readBytes(length);
        try {
            throw new MimeParsingException("Invalid boundary: " + new String(ByteBufUtil.getBytes(byteBuf)));
        } finally {
            byteBuf.release();
        }
    }

    /**
     * Reads the lines for a single header.
     *
     * @return a header line or an empty string if the blank line separating the
     *         header from the body has been reached, or {@code null} if the there is
     *         no more data in the buffer
     */
    @Nullable
    private String readHeaderLine() {
        final int length = in.readableBytes();
        // need more data to progress
        // need at least one blank line to read (no headers)
        if (length == 0) {
            return null;
        }
        int headerLength = 0;
        int lwsp = 0;

        // Find the end of a header line which ends with `\n` or `\r\n`
        for (; headerLength < length; headerLength++) {
            final byte currentChar = in.getByte(headerLength);
            if (currentChar == '\n') {
                lwsp += 1;
                break;
            }
            if (headerLength + 1 >= length) {
                // No more data in the buffer
                return null;
            }
            if (currentChar == '\r' && in.getByte(headerLength + 1) == '\n') {
                lwsp += 2;
                break;
            }
        }
        if (headerLength == 0) {
            in.skipBytes(lwsp);
            return "";
        }

        final ByteBuf byteBuf = in.readBytes(headerLength);
        try {
            in.skipBytes(lwsp);
            return new String(ByteBufUtil.getBytes(byteBuf), HEADER_ENCODING);
        } finally {
            byteBuf.release();
        }
    }

    /**
     * Boyer-Moore search method.
     * Copied from {@link Pattern}
     *
     * <p>Pre calculates arrays needed to generate the bad character shift and the
     * good suffix shift. Only the last seven bits are used to see if chars
     * match; This keeps the tables small and covers the heavily used ASCII
     * range, but occasionally results in an aliased match for the bad character
     * shift.
     */
    private void compileBoundaryPattern() {
        int i;
        int j;

        // Precalculate part of the bad character shift
        // It is a table for where in the pattern each
        // lower 7-bit value occurs
        for (i = 0; i < boundaryBytes.length; i++) {
            badCharacters[boundaryBytes[i] & 0x7F] = i + 1;
        }

        // Precalculate the good suffix shift
        // i is the shift amount being considered
        NEXT:
        for (i = boundaryBytes.length; i > 0; i--) {
            // j is the beginning index of suffix being considered
            for (j = boundaryBytes.length - 1; j >= i; j--) {
                // Testing for good suffix
                if (boundaryBytes[j] == boundaryBytes[j - i]) {
                    // src[j..len] is a good suffix
                    goodSuffixes[j - 1] = i;
                } else {
                    // No match. The array has already been
                    // filled up with correct values before.
                    continue NEXT;
                }
            }
            // This fills up the remaining of optoSft
            // any suffix can not have larger shift amount
            // then its sub-suffix. Why???
            while (j > 0) {
                goodSuffixes[--j] = i;
            }
        }
        // Set the guard value because of unicode compression
        goodSuffixes[boundaryBytes.length - 1] = 1;
    }

    /**
     * Finds the boundary in the given buffer using Boyer-Moore algorithm.
     * Copied from {@link Pattern}
     *
     * @return -1 if there is no match or index where the match starts
     */
    private int match() {
        final int last = in.readableBytes() - boundaryBytes.length;
        int off = 0;

        // Loop over all possible match positions in text
        NEXT:
        while (off <= last) {
            // Loop over pattern from right to left
            for (int j = boundaryBytes.length - 1; j >= 0; j--) {
                final byte ch = in.getByte(off + j);
                if (ch != boundaryBytes[j]) {
                    // Shift search to the right by the maximum of the
                    // bad character shift and the good suffix shift
                    off += Math.max(j + 1 - badCharacters[ch & 0x7F], goodSuffixes[j]);
                    continue NEXT;
                }
            }
            // Entire pattern matched starting at off
            return off;
        }
        return -1;
    }

    /**
     * Gets the bytes representation of a string.
     * @param str string to convert
     * @return byte[]
     */
    private static byte[] getBytes(String str) {
        final char[] chars = str.toCharArray();
        final int size = chars.length;
        final byte[] bytes = new byte[size];

        for (int i = 0; i < size;) {
            bytes[i] = (byte) chars[i++];
        }
        return bytes;
    }

    /**
     * All states.
     */
    private enum State {
        /**
         * The first state set by the parser. It is set only once.
         */
        START_MESSAGE,

        /**
         * The state set when skipping the preamble. It is set only once.
         */
        SKIP_PREAMBLE,

        /**
         * The state set when a new part is detected. It is set for each part.
         */
        START_PART,

        /**
         * The state set for each header line of a part.
         */
        HEADERS,

        /**
         * The state set when each part chunk is being parsed.
         */
        BODY,

        /**
         * The state set when the content for a part is complete. It is set only once for each part.
         */
        END_PART,

        /**
         * The state set when all parts are complete. It is set only once.
         */
        END_MESSAGE
    }
}
