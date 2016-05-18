/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableList;

public interface AggregatedHttpMessage {

    static AggregatedHttpMessage of(HttpMethod method, String path) {
        return of(HttpHeaders.of(method, path));
    }

    static AggregatedHttpMessage of(HttpMethod method, String path, HttpData content) {
        return of(HttpHeaders.of(method, path), content);
    }

    static AggregatedHttpMessage of(int statusCode) {
        return of(HttpStatus.valueOf(statusCode));
    }

    static AggregatedHttpMessage of(HttpStatus status) {
        return of(HttpHeaders.of(status));
    }

    static AggregatedHttpMessage of(HttpStatus status, HttpData content) {
        return of(HttpHeaders.of(status), content);
    }

    static AggregatedHttpMessage of(HttpHeaders headers) {
        return of(headers, HttpData.EMPTY_DATA, HttpHeaders.EMPTY_HEADERS);
    }
    static AggregatedHttpMessage of(HttpHeaders headers, HttpData content) {
        return of(headers, content, HttpHeaders.EMPTY_HEADERS);
    }

    static AggregatedHttpMessage of(HttpHeaders headers, HttpData content, HttpHeaders trailingHeaders) {
        return of(Collections.emptyList(), headers, content, trailingHeaders);
    }

    static AggregatedHttpMessage of(Iterable<HttpHeaders> informationals, HttpHeaders headers,
                                    HttpData content, HttpHeaders trailingHeaders) {

        requireNonNull(informationals, "informationals");
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailingHeaders, "trailingHeaders");

        return new DefaultAggregatedHttpMessage(ImmutableList.copyOf(informationals),
                                                headers, content, trailingHeaders);
    }

    List<HttpHeaders> informationals();

    HttpHeaders headers();

    HttpHeaders trailingHeaders();

    HttpData content();

    default String scheme() {
        return headers().scheme();
    }

    default HttpMethod method() {
        return headers().method();
    }

    default String path() {
        return headers().path();
    }

    default String authority() {
        return headers().authority();
    }

    default HttpStatus status() {
        return headers().status();
    }
}
