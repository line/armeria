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

import java.nio.charset.Charset;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Parser for multipart MIME message.
 */
final class MimeParser {

    // Forked from https://github.com/oracle/helidon/blob/a9363a3d226a3154e2fb99abe230239758504436/media/multipart/src/main/java/io/helidon/media/multipart/MimeParser.java
    // - Replaced VirtualBuffer with CompositeByteBuf

    /**
     * All states.
     */
    private enum State {
        START_MESSAGE,
        SKIP_PREAMBLE,
        START_PART,
        HEADERS,
        BODY,
        END_PART,
        END_MESSAGE,
        DATA_REQUIRED
    }

    private static final Logger logger = LoggerFactory.getLogger(MimeParser.class);

    private static final ByteBuf EMPTY_BUF = Unpooled.buffer(1);
    private static final Charset HEADER_ENCODING = Charset.forName("ISO8859-1");
    private static final StartMessageEvent START_MESSAGE_EVENT = new StartMessageEvent();
    private static final StartPartEvent START_PART_EVENT = new StartPartEvent();
    private static final EndHeadersEvent END_HEADERS_EVENT = new EndHeadersEvent();
    private static final EndPartEvent END_PART_EVENT = new EndPartEvent();
    private static final EndMessageEvent END_MESSAGE_EVENT = new EndMessageEvent();

    /**
     * The current parser state.
     */
    private State state = State.START_MESSAGE;

    /**
     * The parser state to resume to, non {@code null} when {@link #state} is
     * equal to {@link State#DATA_REQUIRED}.
     */
    @Nullable
    private State resumeState;

    /**
     * Boundary as bytes.
     */
    private final byte[] boundaryBytes;

    /**
     * Boundary length.
     */
    private final int boundaryLength;

    /**
     * BnM algorithm: Bad Character Shift table.
     */
    private final int[] badCharacters = new int[128];

    /**
     * BnM algorithm : Good Suffix Shift table.
     */
    private final int[] goodSuffixes;

    /**
     * Read and process body partsList until we see the terminating boundary
     * line.
     */
    private boolean done;

    /**
     * Beginning of the line.
     */
    private boolean startOfLine;

    /**
     * Read-only byte array of the current byte buffer being processed.
     */
    private final CompositeByteBuf buf;

    /**
     * The position of the next boundary.
     */
    private int boundaryStart;

    /**
     * Indicates if this parser is closed.
     */
    private boolean closed;

    /**
     * The event listener.
     */
    private final EventProcessor listener;

    /**
     * Parses the MIME content.
     */
    MimeParser(String boundary, EventProcessor eventListener) {
        boundaryBytes = getBytes("--" + boundary);
        listener = eventListener;
        boundaryLength = boundaryBytes.length;
        goodSuffixes = new int[boundaryLength];
        buf = Unpooled.compositeBuffer();
        compileBoundaryPattern();
    }

    /**
     * Pushes new data to the parsing buffer.
     *
     * @param data new data add to the parsing buffer
     * @throws MimeParsingException if the parser state is not consistent
     */
    void offer(ByteBuf data) {
        if (closed) {
            throw new MimeParsingException("Parser is closed");
        }
        switch (state) {
            case START_MESSAGE:
                buf.addComponent(true, data);
                buf.readerIndex(0);
                break;
            case DATA_REQUIRED:
                // resume the previous state
                state = resumeState;
                resumeState = null;
                buf.discardSomeReadBytes();
                buf.addComponents(true, data);
                break;
            default:
                throw new MimeParsingException("Invalid state: " + state);
        }
    }

    /**
     * Marks this parser instance as closed. Invoking this method indicates that
     * no more data will be pushed to the parsing buffer.
     *
     * @throws MimeParsingException if the parser state is not {@code END_MESSAGE} or {@code START_MESSAGE}
     */
    void close() {
        switch (state) {
            case START_MESSAGE:
            case END_MESSAGE:
                closed = true;
                buf.release();
                break;
            case DATA_REQUIRED:
                switch (resumeState) {
                    case SKIP_PREAMBLE:
                        throw new MimeParsingException("Missing start boundary");
                    case BODY:
                        throw new MimeParsingException("No closing MIME boundary");
                    case HEADERS:
                        throw new MimeParsingException("No blank line found");
                    default:
                        // do nothing
                }
                break;
            default:
                throw new MimeParsingException("Invalid state: " + state);
        }
    }

    /**
     * Advances parsing.
     * @throws MimeParsingException if an error occurs during parsing
     */
    void parse() {
        try {
            while (true) {
                switch (state) {
                    case START_MESSAGE:
                        logger.trace("state={}", State.START_MESSAGE);
                        state = State.SKIP_PREAMBLE;
                        listener.process(START_MESSAGE_EVENT);
                        break;

                    case SKIP_PREAMBLE:
                        logger.trace("state={}", State.SKIP_PREAMBLE);
                        skipPreamble();
                        if (boundaryStart == -1) {
                            logger.trace("state={}", State.DATA_REQUIRED);
                            state = State.DATA_REQUIRED;
                            resumeState = State.SKIP_PREAMBLE;
                            listener.process(new DataRequiredEvent(false));
                            return;
                        }
                        logger.trace("Skipped the preamble. position={}", buf.readerIndex());
                        state = State.START_PART;
                        break;

                    // fall through
                    case START_PART:
                        logger.trace("state={}", State.START_PART);
                        state = State.HEADERS;
                        listener.process(START_PART_EVENT);
                        break;

                    case HEADERS:
                        logger.trace("state={}", State.HEADERS);
                        final String headerLine = readHeaderLine();
                        if (headerLine == null) {
                            logger.trace("state={}", State.DATA_REQUIRED);
                            state = State.DATA_REQUIRED;
                            resumeState = State.HEADERS;
                            listener.process(new DataRequiredEvent(false));
                            return;
                        }
                        if (!headerLine.isEmpty()) {
                            final Header header = new Header(headerLine);
                            listener.process(new HeaderEvent(header.name(), header.value()));
                            break;
                        }
                        state = State.BODY;
                        startOfLine = true;
                        listener.process(END_HEADERS_EVENT);
                        break;

                    case BODY:
                        logger.trace("state={}", State.BODY);
                        final ByteBuf bodyContent = readBody();
                        if (boundaryStart == -1 || bodyContent == EMPTY_BUF) {
                            logger.trace("state={}", State.DATA_REQUIRED);
                            state = State.DATA_REQUIRED;
                            resumeState = State.BODY;
                            if (bodyContent == EMPTY_BUF) {
                                listener.process(new DataRequiredEvent(true));
                                return;
                            }
                        } else {
                            startOfLine = false;
                        }
                        listener.process(new ContentEvent(bodyContent));
                        break;

                    case END_PART:
                        logger.trace("state={}", State.END_PART);
                        if (done) {
                            state = State.END_MESSAGE;
                        } else {
                            state = State.START_PART;
                        }
                        listener.process(END_PART_EVENT);
                        break;

                    case END_MESSAGE:
                        logger.trace("state={}", State.END_MESSAGE);
                        listener.process(END_MESSAGE_EVENT);
                        return;

                    case DATA_REQUIRED:
                        listener.process(new DataRequiredEvent(resumeState == State.BODY));
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
     *
     * @return list of read-only ByteBuffer, or {@code EMPTY_BUF} if more data is
     *         required and no body content can be returned.
     */
    private ByteBuf readBody() {
        // matches boundary
        boundaryStart = match();
        final int length = buf.capacity();
        final int bodyStart = buf.readerIndex();

        if (boundaryStart == -1) {
            // No boundary is found
            if (bodyStart + boundaryLength + 1 < length) {
                // there may be an incomplete boundary at the end of the buffer
                // return the remaining data minus the boundary length
                // so that it can be processed next iteration
                final int bodyLength = length - bodyStart - (boundaryLength + 1);
                return buf.readSlice(bodyLength);
            }
            // remaining data can be an complete boundary, force it to be
            // processed during next iteration
            return EMPTY_BUF;
        }

        // Found boundary.
        // Is it at the start of a line ?
        int bodyLength = boundaryStart - bodyStart;
        if (startOfLine && bodyLength == 0) {
            // nothing to do
        } else if (boundaryStart > bodyStart &&
                   (buf.getByte(boundaryStart - 1) == '\n' ||
                    buf.getByte(boundaryStart - 1) == '\r')) {
            --bodyLength;
            if (buf.getByte(boundaryStart - 1) == '\n' && boundaryStart > 1 &&
                buf.getByte(boundaryStart - 2) == '\r') {
                --bodyLength;
            }
        } else {
            // boundary is not at beginning of a line
            return buf.readSlice(bodyLength + 1);
        }

        // check if this is a "closing" boundary
        if (boundaryStart + boundaryLength + 1 < length &&
            buf.getByte(boundaryStart + boundaryLength) == '-' &&
            buf.getByte(boundaryStart + boundaryLength + 1) == '-') {

            state = State.END_PART;
            done = true;
            final ByteBuf body = buf.readSlice(bodyLength);
            buf.readerIndex(boundaryStart + boundaryLength + 2);
            return body;
        }

        // Consider all the linear whitespace in boundary+whitespace+"\r\n"
        int linearWhiteSpace = 0;
        for (int i = boundaryStart + boundaryLength;
             i < length && (buf.getByte(i) == ' ' || buf.getByte(i) == '\t'); i++) {
            ++linearWhiteSpace;
        }

        // Check boundary+whitespace+"\n"
        if (boundaryStart + boundaryLength + linearWhiteSpace < length &&
            buf.getByte(boundaryStart + boundaryLength + linearWhiteSpace) == '\n') {

            state = State.END_PART;
            final ByteBuf body = buf.readSlice(bodyLength);
            buf.readerIndex(boundaryStart + boundaryLength + linearWhiteSpace + 1);
            return body;
        }

        // Check for boundary+whitespace+"\r\n"
        if (boundaryStart + boundaryLength + linearWhiteSpace + 1 < length &&
            buf.getByte(boundaryStart + boundaryLength + linearWhiteSpace) == '\r' &&
            buf.getByte(boundaryStart + boundaryLength + linearWhiteSpace + 1) == '\n') {

            state = State.END_PART;
            final ByteBuf body = buf.readSlice(bodyLength);
            buf.readerIndex(boundaryStart + boundaryLength + linearWhiteSpace + 2);
            return body;
        }

        if (boundaryStart + boundaryLength + linearWhiteSpace + 1 < length) {
            // boundary string in a part data
            return buf.readSlice(bodyLength + 1);
        }

        // A boundary is found but it's not a "closing" boundary
        // return everything before that boundary as the "closing" characters
        // might be available next iteration
        final ByteBuf body = buf.readSlice(bodyLength);
        buf.readerIndex(boundaryStart);
        return body;
    }

    /**
     * Skips the preamble.
     */
    private void skipPreamble() {
        // matches boundary
        boundaryStart = match();
        if (boundaryStart == -1) {
            // No boundary is found
            return;
        }

        final int length = buf.capacity();

        // Consider all the whitespace boundary+whitespace+"\r\n"
        int linearWhiteSpace = 0;
        for (int i = boundaryStart + boundaryLength;
             i < length && (buf.getByte(i) == ' ' || buf.getByte(i) == '\t'); i++) {
            ++linearWhiteSpace;
        }

        // Check for \n or \r\n
        if (boundaryStart + boundaryLength + linearWhiteSpace < length &&
            (buf.getByte(boundaryStart + boundaryLength + linearWhiteSpace) == '\n' ||
             buf.getByte(boundaryStart + boundaryLength + linearWhiteSpace) == '\r')) {

            if (buf.getByte(boundaryStart + boundaryLength + linearWhiteSpace) == '\n') {
                buf.readerIndex(boundaryStart + boundaryLength + linearWhiteSpace + 1);
                return;
            } else if (boundaryStart + boundaryLength + linearWhiteSpace + 1 < length &&
                       buf.getByte(boundaryStart + boundaryLength + linearWhiteSpace + 1) == '\n') {
                buf.readerIndex(boundaryStart + boundaryLength + linearWhiteSpace + 2);
                return;
            }
        }
        buf.readerIndex(boundaryStart + 1);
    }

    /**
     * Read the lines for a single header.
     *
     * @return a header line or an empty string if the blank line separating the
     *         header from the body has been reached, or {@code null} if the there is
     *         no more data in the buffer
     */
    @Nullable
    private String readHeaderLine() {
        final int length = buf.capacity();
        // need more data to progress
        // need at least one blank line to read (no headers)
        final int readerIndex = buf.readerIndex();
        if (readerIndex >= length - 1) {
            return null;
        }
        int headerLength = 0;
        int lwsp = 0;
        for (; readerIndex + headerLength < length; headerLength++) {
            if (buf.getByte(readerIndex + headerLength) == '\n') {
                lwsp += 1;
                break;
            }
            if (readerIndex + headerLength + 1 >= length) {
                // No more data in the buffer
                return null;
            }
            if (buf.getByte(readerIndex + headerLength) == '\r' &&
                buf.getByte(readerIndex + headerLength + 1) == '\n') {
                lwsp += 2;
                break;
            }
        }
        buf.readerIndex(readerIndex + headerLength + lwsp);
        if (headerLength == 0) {
            return "";
        }
        return new String(ByteBufUtil.getBytes(buf, readerIndex, headerLength), HEADER_ENCODING);
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
        final int last = buf.capacity() - boundaryBytes.length;
        int off = buf.readerIndex();

        // Loop over all possible match positions in text
        NEXT:
        while (off <= last) {
            // Loop over pattern from right to left
            for (int j = boundaryBytes.length - 1; j >= 0; j--) {
                final byte ch = buf.getByte(off + j);
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
     * Get the bytes representation of a string.
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
     * A private utility class to represent an individual header.
     */
    private static final class Header {

        /**
         * The trimmed name of this header.
         */
        private final String name;

        /**
         * The entire header "line".
         */
        private final String line;

        /**
         * Constructor that takes a line and splits out the header name.
         */
        private Header(String line) {
            final int i = line.indexOf(':');
            if (i < 0) {
                // should never happen
                name = line.trim();
            } else {
                name = line.substring(0, i).trim();
            }
            this.line = line;
        }

        /**
         * Return the "name" part of the header line.
         */
        String name() {
            return name;
        }

        /**
         * Return the "value" part of the header line.
         */
        String value() {
            final int i = line.indexOf(':');
            if (i < 0) {
                return line;
            }

            int j;
            // skip whitespace after ':'
            for (j = i + 1; j < line.length(); j++) {
                final char c = line.charAt(j);
                if (!(c == ' ' || c == '\t')) {
                    break;
                }
            }
            return line.substring(j);
        }
    }

    /**
     * The emitted parser event types.
     */
    enum EventType {

        /**
         * This event is the first event issued by the parser.
         * It is generated only once.
         */
        START_MESSAGE,

        /**
         * This event is issued when a new part is detected.
         * It is generated for each part.
         */
        START_PART,

        /**
         * This event is issued for each header line of a part. It may be
         * generated more than once for each part.
         */
        HEADER,

        /**
         * This event is issued for each header line of a part. It may be
         * generated more than once for each part.
         */
        END_HEADERS,

        /**
         * This event is issued for each part chunk parsed. The event
         * It may be generated more than once for each part.
         */
        CONTENT,

        /**
         * This event is issued when the content for a part is complete.
         * It is generated only once for each part.
         */
        END_PART,

        /**
         * This event is issued when all parts are complete. It is generated
         * only once.
         */
        END_MESSAGE,

        /**
         * This event is issued when there is not enough data in the buffer to
         * continue parsing. If issued after:
         * <ul>
         * <li>{@link #START_MESSAGE} - the parser did not detect the end of
         * the preamble</li>
         * <li>{@link #HEADER} - the parser
         * did not detect the blank line that separates the part headers and the
         * part body</li>
         * <li>{@link #CONTENT} - the parser did not
         * detect the next starting boundary or closing boundary</li>
         * </ul>
         */
        DATA_REQUIRED
    }

    /**
     * Base class for the parser events.
     */
    abstract static class ParserEvent {

        /**
         * Get the event type.
         * @return EVENT_TYPE
         */
        abstract EventType type();

        /**
         * Get this event as a {@link HeaderEvent}.
         * @return HeaderEvent
         */
        HeaderEvent asHeaderEvent() {
            return (HeaderEvent) this;
        }

        /**
         * Get this event as a {@link ContentEvent}.
         *
         * @return ContentEvent
         */
        ContentEvent asContentEvent() {
            return (ContentEvent) this;
        }

        /**
         * Get this event as a {@link DataRequiredEvent}.
         *
         * @return DataRequiredEvent
         */
        DataRequiredEvent asDataRequiredEvent() {
            return (DataRequiredEvent) this;
        }
    }

    /**
     * The event class for {@link EventType#START_MESSAGE}.
     */
    static final class StartMessageEvent extends ParserEvent {

        private StartMessageEvent() {
        }

        @Override
        EventType type() {
            return EventType.START_MESSAGE;
        }
    }

    /**
     * The event class for {@link EventType#START_MESSAGE}.
     */
    static final class StartPartEvent extends ParserEvent {

        private StartPartEvent() {
        }

        @Override
        EventType type() {
            return EventType.START_PART;
        }
    }

    /**
     * The event class for {@link EventType#HEADER}.
     */
    static final class HeaderEvent extends ParserEvent {

        private final String name;
        private final String value;

        private HeaderEvent(String name, String value) {
            this.name = name;
            this.value = value;
        }

        String name() {
            return name;
        }

        String value() {
            return value;
        }

        @Override
        EventType type() {
            return EventType.HEADER;
        }
    }

    /**
     * The event class for {@link EventType#END_HEADERS}.
     */
    static final class EndHeadersEvent extends ParserEvent {

        private EndHeadersEvent() {
        }

        @Override
        EventType type() {
            return EventType.END_HEADERS;
        }
    }

    /**
     * The event class for {@link EventType#CONTENT}.
     */
    static final class ContentEvent extends ParserEvent {

        private final ByteBuf bufferEntry;

        ContentEvent(ByteBuf data) {
            bufferEntry = data;
        }

        ByteBuf content() {
            return bufferEntry;
        }

        @Override
        EventType type() {
            return EventType.CONTENT;
        }
    }

    /**
     * The event class for {@link EventType#END_PART}.
     */
    static final class EndPartEvent extends ParserEvent {

        private EndPartEvent() {
        }

        @Override
        EventType type() {
            return EventType.END_PART;
        }
    }

    /**
     * The event class for {@link EventType#END_MESSAGE}.
     */
    static final class EndMessageEvent extends ParserEvent {

        private EndMessageEvent() {
        }

        @Override
        EventType type() {
            return EventType.END_MESSAGE;
        }
    }

    /**
     * The event class for {@link EventType#DATA_REQUIRED}.
     */
    static final class DataRequiredEvent extends ParserEvent {

        private final boolean content;

        private DataRequiredEvent(boolean content) {
            this.content = content;
        }

        /**
         * Indicates if the required data is for the body content of a part.
         * @return {@code true} if for body content, {@code false} otherwise
         */
        boolean isContent() {
            return content;
        }

        @Override
        EventType type() {
            return EventType.DATA_REQUIRED;
        }
    }

    /**
     * Callback interface to the parser.
     */
    @FunctionalInterface
    interface EventProcessor {

        /**
         * Process a parser event.
         * @param event generated event
         */
        void process(ParserEvent event);
    }
}
