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

package com.linecorp.armeria.common.throttling;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaderNames;

import io.netty.util.AsciiString;

final class ThrottlingHeadersImpl implements ThrottlingHeaders {

    private static final String LIMIT_SUFFIX = "-Limit";
    private static final String REMAINING_SUFFIX = "-Remaining";
    private static final String RESET_SUFFIX = "-Reset";

    private final AsciiString limitHeader;
    private final AsciiString remainingHeader;
    private final AsciiString resetHeader;

    ThrottlingHeadersImpl(final String scheme) {
        limitHeader = HttpHeaderNames.of(scheme + LIMIT_SUFFIX);
        remainingHeader = HttpHeaderNames.of(scheme + REMAINING_SUFFIX);
        resetHeader = HttpHeaderNames.of(scheme + RESET_SUFFIX);
    }

    @Override
    public AsciiString limitHeader() {
        return limitHeader;
    }

    @Override
    public AsciiString remainingHeader() {
        return remainingHeader;
    }

    @Override
    public AsciiString resetHeader() {
        return resetHeader;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("limitHeader", limitHeader)
                          .add("remainingHeader", remainingHeader)
                          .add("resetHeader", resetHeader)
                          .toString();
    }
}
