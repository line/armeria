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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

/**
 * An immutable {@link Set} of {@link MediaType}s which provides useful methods for content negotiation.
 *
 * <p>This {@link Set} provides {@link #match(MediaType)} and {@link #matchHeaders(CharSequence...)}
 * so that a user can find the preferred {@link MediaType} that matches the specified media ranges. For example:
 * <pre>{@code
 * MediaTypeSet set = MediaTypeSet.of(MediaType.HTML_UTF_8, MediaType.PLAIN_TEXT_UTF_8);
 *
 * MediaType negotiated1 = set.matchHeaders("text/html; q=0.5, text/plain");
 * assert negotiated1 != null;
 * assert negotiated1.equals(MediaType.PLAIN_TEXT_UTF_8);
 *
 * MediaType negotiated2 = set.matchHeaders("audio/*, text/*");
 * assert negotiated2 != null;
 * assert negotiated2.equals(MediaType.HTML_UTF_8);
 *
 * MediaType negotiated3 = set.matchHeaders("video/webm");
 * assert negotiated3 == null;
 * }</pre>
 */
public final class MediaTypeSet extends AbstractSet<MediaType> {

    private static final String Q = "q";
    private static final MediaType[] EMPTY_MEDIA_TYPES = new MediaType[0];

    private final MediaType[] mediaTypes;

    /**
     * Returns the {@link MediaTypeSet} which is composed of the specified {@link MediaType}s.
     */
    public static MediaTypeSet of(MediaType... mediaTypes) {
        return new MediaTypeSet(ImmutableList.copyOf(requireNonNull(mediaTypes, "mediaTypes")));
    }

    /**
     * Returns the {@link MediaTypeSet} which is composed of the specified {@link MediaType}s.
     */
    public static MediaTypeSet of(Iterable<MediaType> mediaTypes) {
        if (mediaTypes instanceof MediaTypeSet) {
            return (MediaTypeSet) mediaTypes;
        }
        return new MediaTypeSet(ImmutableList.copyOf(requireNonNull(mediaTypes, "mediaTypes")));
    }

    private MediaTypeSet(Iterable<MediaType> mediaTypes) {
        final Set<MediaType> mediaTypesCopy = new LinkedHashSet<>(); // Using a Set to deduplicate
        for (MediaType mediaType : requireNonNull(mediaTypes, "mediaTypes")) {
            requireNonNull(mediaType, "mediaTypes contains null.");
            checkArgument(!mediaType.hasWildcard(),
                          "mediaTypes contains a wildcard media type: %s", mediaType);

            // Ensure qvalue does not exists.
            final List<String> otherQValues = mediaType.parameters().get(Q);
            checkArgument(otherQValues == null || otherQValues.isEmpty(),
                          "mediaTypes contains a media type with a q-value parameter: %s", mediaType);

            mediaTypesCopy.add(mediaType);
        }
        checkArgument(!mediaTypesCopy.isEmpty(), "mediaTypes is empty.");
        this.mediaTypes = mediaTypesCopy.toArray(EMPTY_MEDIA_TYPES);
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof MediaType)) {
            return false;
        }
        for (MediaType e : mediaTypes) {
            if (e.equals(o)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<MediaType> iterator() {
        return Iterators.forArray(mediaTypes);
    }

    @Override
    public int size() {
        return mediaTypes.length;
    }

    /**
     * Finds the {@link MediaType} in this {@link List} that matches one of the media ranges specified in the
     * specified string.
     *
     * @param acceptHeaders the values of the {@code "accept"} header, as defined in
     *        <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.1">the section 14.1, RFC2616</a>
     * @return the most preferred {@link MediaType} that matches one of the specified media ranges.
     *         {@code null} if there are no matches or {@code acceptHeaders} does not contain any valid ranges.
     */
    @Nullable
    public MediaType matchHeaders(Iterable<? extends CharSequence> acceptHeaders) {
        requireNonNull(acceptHeaders, "acceptHeaders");

        final List<MediaType> ranges = new ArrayList<>(4);
        for (CharSequence acceptHeader : acceptHeaders) {
            addRanges(ranges, acceptHeader);
        }
        return match(ranges);
    }

    /**
     * Finds the {@link MediaType} in this {@link List} that matches one of the media ranges specified in the
     * specified string.
     *
     * @param acceptHeaders the values of the {@code "accept"} header, as defined in
     *        <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.1">the section 14.1, RFC2616</a>
     * @return the most preferred {@link MediaType} that matches one of the specified media ranges.
     *         {@code null} if there are no matches or {@code acceptHeaders} does not contain any valid ranges.
     */
    @Nullable
    public MediaType matchHeaders(CharSequence... acceptHeaders) {
        requireNonNull(acceptHeaders, "acceptHeaders");

        final List<MediaType> ranges = new ArrayList<>(4);
        for (CharSequence acceptHeader : acceptHeaders) {
            addRanges(ranges, acceptHeader);
        }
        return match(ranges);
    }

    /**
     * Finds the {@link MediaType} in this {@link List} that matches the specified media range.
     *
     * @return the {@link MediaType} that matches the specified media range.
     *         {@code null} if there are no matches
     */
    @Nullable
    public MediaType match(MediaType range) {
        requireNonNull(range, "range");
        for (MediaType candidate : mediaTypes) {
            if (candidate.belongsTo(range)) {
                // With only one specified range, there is no way for candidates to have priority over each
                // other, we just return the first match.
                return candidate;
            }
        }
        return null;
    }

    /**
     * Finds the {@link MediaType} in this {@link List} that matches one of the specified media ranges.
     *
     * @return the most preferred {@link MediaType} that matches one of the specified media ranges.
     *         {@code null} if there are no matches or the specified ranges do not contain any valid ranges.
     */
    @Nullable
    public MediaType match(MediaType first, MediaType... rest) {
        requireNonNull(first, "first");
        requireNonNull(rest, "rest");
        return match(Lists.asList(first, rest));
    }

    /**
     * Finds the {@link MediaType} in this {@link List} that matches one of the specified media ranges.
     *
     * @return the most preferred {@link MediaType} that matches one of the specified media ranges.
     *         {@code null} if there are no matches or {@code ranges} does not contain any valid ranges.
     */
    @Nullable
    public MediaType match(Iterable<MediaType> ranges) {
        requireNonNull(ranges, "ranges");

        MediaType match = null;
        float matchQ = Float.NEGATIVE_INFINITY;    // higher = better
        int matchNumWildcards = Integer.MAX_VALUE; // lower = better
        int matchNumParams = Integer.MIN_VALUE;    // higher = better
        for (MediaType range : ranges) {
            requireNonNull(range, "ranges contains null.");
            float qValue = range.qualityFactor(Float.NEGATIVE_INFINITY);
            final int numWildcards = range.numWildcards();
            final int numParams;
            if (qValue < 0) {
                // qvalue does not exist; use the default value of 1.0.
                qValue = 1.0f;
                numParams = range.parameters().size();
            } else {
                // Do not count the qvalue.
                numParams = range.parameters().size() - 1;
            }

            for (MediaType candidate : mediaTypes) {
                if (!candidate.belongsTo(range)) {
                    continue;
                }

                final boolean isBetter;
                if (qValue > matchQ) {
                    isBetter = true;
                } else if (Math.copySign(qValue - matchQ, 1.0f) <= 0.0001f) {
                    if (matchNumWildcards > numWildcards) {
                        isBetter = true;
                    } else if (matchNumWildcards == numWildcards) {
                        isBetter = numParams > matchNumParams;
                    } else {
                        isBetter = false;
                    }
                } else {
                    isBetter = false;
                }

                if (isBetter) {
                    match = candidate;
                    matchQ = qValue;
                    matchNumWildcards = numWildcards;
                    matchNumParams = numParams;

                    // Won't find another better candidate for this range.
                    break;
                }
            }
        }

        return match;
    }

    private static final int ST_SKIP_LEADING_WHITESPACES = 0;
    private static final int ST_READ_QDTEXT = 1;
    private static final int ST_READ_QUOTED_STRING = 2;
    private static final int ST_READ_QUOTED_STRING_ESCAPED = 3;

    @VisibleForTesting
    @SuppressWarnings("checkstyle:FallThrough")
    static void addRanges(List<MediaType> ranges, CharSequence header) {
        final int length = header.length();
        int state = ST_SKIP_LEADING_WHITESPACES;
        int firstCharIdx = 0;
        int lastCharIdx = -1;
        for (int i = 0; i < length; i++) {
            final char ch = header.charAt(i);
            switch (state) {
                case ST_SKIP_LEADING_WHITESPACES:
                    if (!Character.isWhitespace(ch)) {
                        state = ST_READ_QDTEXT;
                        firstCharIdx = i;
                        lastCharIdx = -1;
                    } else {
                        break;
                    }
                case ST_READ_QDTEXT:
                    if (Character.isWhitespace(ch)) {
                        break;
                    }
                    switch (ch) {
                        case '"':
                            state = ST_READ_QUOTED_STRING;
                            break;
                        case ',':
                            addRange(ranges, header, firstCharIdx, lastCharIdx);
                            state = ST_SKIP_LEADING_WHITESPACES;
                            break;
                        default:
                            lastCharIdx = i;
                    }
                    break;
                case ST_READ_QUOTED_STRING:
                    switch (ch) {
                        case '\\':
                            state = ST_READ_QUOTED_STRING_ESCAPED;
                            break;
                        case '"':
                            state = ST_READ_QDTEXT;
                            lastCharIdx = i;
                            break;
                    }
                    break;
                case ST_READ_QUOTED_STRING_ESCAPED:
                    state = ST_READ_QUOTED_STRING;
                    break;
            }
        }

        if (state == ST_READ_QDTEXT) {
            addRange(ranges, header, firstCharIdx, lastCharIdx);
        }
    }

    private static void addRange(
            List<MediaType> ranges, CharSequence header, int firstCharIdx, int lastCharIdx) {
        if (lastCharIdx >= 0) {
            try {
                ranges.add(MediaType.parse(header.subSequence(firstCharIdx, lastCharIdx + 1).toString()));
            } catch (IllegalArgumentException e) {
                // Ignore the malformed media range.
            }
        }
    }
}
