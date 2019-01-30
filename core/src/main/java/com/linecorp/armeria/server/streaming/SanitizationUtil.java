/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

final class SanitizationUtil {

    private static final Logger logger = LoggerFactory.getLogger(SanitizationUtil.class);

    static HttpHeaders ensureHttpStatus(HttpHeaders headers) {
        return ensureHttpStatus(headers, HttpStatus.OK);
    }

    static HttpHeaders ensureHttpStatus(HttpHeaders headers, HttpStatus expected) {
        final HttpStatus status = headers.status();
        if (status == null) {
            return headers.toMutable().status(expected);
        }

        if (status.equals(expected)) {
            return headers;
        }

        logger.warn("Change HTTP status from '{}' to '{}'", status, expected);
        return headers.toMutable().status(expected);
    }

    static HttpHeaders ensureContentType(HttpHeaders headers, MediaType expected) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return headers.toMutable().contentType(expected);
        }

        if (contentType.is(expected)) {
            return headers;
        }

        logger.warn("Change content-type from '{}' to '{}'", contentType, expected);
        return headers.toMutable().contentType(expected);
    }

    private SanitizationUtil() {}
}
