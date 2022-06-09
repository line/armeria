/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.conditional;

import static com.linecorp.armeria.server.conditional.ETag.parseHeader;
import static com.linecorp.armeria.server.conditional.ETagResponse.PERFORM_METHOD;
import static com.linecorp.armeria.server.conditional.ETagResponse.SKIP_METHOD_NOT_MODIFIED;
import static com.linecorp.armeria.server.conditional.ETagResponse.SKIP_METHOD_PRECONDITION_FAILED;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * This class helps with handling of Conditional Requests as per
 * <a href="https://datatracker.ietf.org/doc/html/rfc2732">RFC 7232</a>.
 */
public final class ConditionalRequestUtil {
    private ConditionalRequestUtil() {}

    @VisibleForTesting
    static boolean strongComparison(List<ETag> eTags, ETag dataETag) {
        if (dataETag.isWeak()) {
            return false;
        }
        for (ETag eTag : eTags) {
            if (eTag.isStrong() && eTag.getETag().equals(dataETag.getETag())) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    static boolean weakComparison(List<ETag> requestETags, ETag dataETag) {
        for (ETag eTag : requestETags) {
            if (eTag.getETag().equals(dataETag.getETag())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Performs an If-Match.
     * @param requestETags The parsed If-None-Match header.
     * @param dataETag The ETag of the current data. If the resource doesn't exists, this is null.
     *      This is mostly used to make sure PUT operations don't overwrite existing data.
     * @return true if the method should be performed (ie. NOT just return 304 Not Modified).
     */
    public static ETagResponse ifMatch(List<ETag> requestETags, @Nullable ETag dataETag) {
        requireNonNull(requestETags, "requestETags cannot be null");
        // Making sure that it's the same instance
        if (requestETags == ETag.ASTERISK_ETAG) {
            if (dataETag != null) {
                return PERFORM_METHOD;
            } else {
                return SKIP_METHOD_PRECONDITION_FAILED;
            }
        }
        if (dataETag == null) {
            return PERFORM_METHOD;
        }
        // If-Match requires strong match which can never match weak tags
        if (strongComparison(requestETags, dataETag)) {
            return PERFORM_METHOD;
        }
        return SKIP_METHOD_PRECONDITION_FAILED;
    }

    /**
     * Performs an If-Match.
     * @param header The If-None-Match header.
     * @param dataETag The ETag of the current data. If the resource doesn't exists, this is null.
     *      This is mostly used to make sure PUT operations don't overwrite existing data.
     * @return true if the method should be performed (ie. NOT just return 304 Not Modified).
     */
    public static ETagResponse ifMatch(@Nullable String header, @Nullable ETag dataETag) {
        final List<ETag> requestETags = parseHeader(header);
        if (requestETags != null) {
            return ifMatch(requestETags, dataETag);
        }
        return PERFORM_METHOD;
    }

    /**
     * Performs an If-None-Match.
     * @param requestETags The parsed If-None-Match header.
     * @param dataETag The ETag of the current data. If the resource doesn't exists, this is null.
     *      This is mostly used to make sure PUT operations don't overwrite existing data.
     * @return true if the method should be performed (ie. NOT just return 304 Not Modified).
     */
    public static ETagResponse ifNoneMatch(List<ETag> requestETags, @Nullable ETag dataETag) {
        requireNonNull(requestETags, "requestETags cannot be null");
        // Making sure that it's the same instance
        if (requestETags == ETag.ASTERISK_ETAG) {
            if (dataETag != null) {
                return SKIP_METHOD_NOT_MODIFIED;
            } else {
                return PERFORM_METHOD;
            }
        }
        if (dataETag != null) {
            if (weakComparison(requestETags, dataETag)) {
                return SKIP_METHOD_NOT_MODIFIED;
            }
        }
        return PERFORM_METHOD;
    }

    /**
     * Performs an If-None-Match.
     * @param header The If-None-Match header.
     * @param dataETag The ETag of the current data. If the resource doesn't exists, this is null.
     *      This is mostly used to make sure PUT operations don't overwrite existing data.
     * @return true if the method should be performed (ie. NOT just return 304 Not Modified).
     */
    public static ETagResponse ifNoneMatch(@Nullable String header, @Nullable ETag dataETag) {
        final List<ETag> requestETags = parseHeader(header);
        if (requestETags != null) {
            return ifNoneMatch(requestETags, dataETag);
        }
        return PERFORM_METHOD;
    }

    /**
     * Processes a request doing If-Match, If-None-Match, If-Unmodified-Since and If-Modified-Since.
     * @param reqHeaders The RequestHeaders object of the request.
     * @param dataETag The ETag of the current data. If the resource doesn't exists, this is null.
     *      This is mostly used to make sure PUT operations don't overwrite existing data.
     * @return HttpStatus to return. PRECONDITION_FAILED for when If-Match matches.
     */
    public static ETagResponse conditionalRequest(RequestHeaders reqHeaders,
                                                  @Nullable ETag dataETag,
                                                  @Nullable Long dataLastModified) {
        requireNonNull(reqHeaders, "reqHeaders cannot be null");
        final List<ETag> ifMatchHeader = parseHeader(reqHeaders.get(HttpHeaderNames.IF_MATCH));
        if (ifMatchHeader != null && ifMatch(ifMatchHeader, dataETag) == SKIP_METHOD_PRECONDITION_FAILED) {
            return SKIP_METHOD_PRECONDITION_FAILED;
        }
        final String ifNoneMatchHeader = reqHeaders.get(HttpHeaderNames.IF_NONE_MATCH);
        if (ifNoneMatchHeader != null) {
            final ETagResponse ifNoneMatchResponse = ifNoneMatch(ifNoneMatchHeader, dataETag);
            if (ifNoneMatchResponse == SKIP_METHOD_NOT_MODIFIED) {
                return SKIP_METHOD_NOT_MODIFIED;
            } else {
                // Handle 'if-modified-since' header, only if 'if-none-match' does not exist.
                // ie return here, because If-None-Match is definitive.
                return PERFORM_METHOD;
            }
        }

        if (dataLastModified != null) {
            try {
                final Long ifModifiedSince = reqHeaders.getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE);
                if (ifModifiedSince != null) {
                    if (dataLastModified / 1000 <= ifModifiedSince / 1000) {
                        return SKIP_METHOD_NOT_MODIFIED;
                    }
                }
            } catch (Exception e) {
                // Malformed date.
            }
            try {
                final Long ifUnmodifiedSince = reqHeaders.getTimeMillis(HttpHeaderNames.IF_UNMODIFIED_SINCE);
                if (ifUnmodifiedSince != null) {
                    if (dataLastModified / 1000 >= ifUnmodifiedSince / 1000) {
                        return SKIP_METHOD_NOT_MODIFIED;
                    }
                }
            } catch (Exception e) {
                // Malformed date.
            }
        }
        return PERFORM_METHOD;
    }
}
