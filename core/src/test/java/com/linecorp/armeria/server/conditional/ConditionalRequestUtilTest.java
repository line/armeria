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

import static com.linecorp.armeria.common.HttpHeaderNames.IF_MATCH;
import static com.linecorp.armeria.common.HttpHeaderNames.IF_MODIFIED_SINCE;
import static com.linecorp.armeria.common.HttpHeaderNames.IF_NONE_MATCH;
import static com.linecorp.armeria.server.conditional.ConditionalRequestUtil.conditionalRequest;
import static com.linecorp.armeria.server.conditional.ConditionalRequestUtil.strongComparison;
import static com.linecorp.armeria.server.conditional.ConditionalRequestUtil.weakComparison;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;

class ConditionalRequestUtilTest {
    private static final Long LONG_TIME_AGO = Instant.parse("2007-12-03T10:15:30.00Z").toEpochMilli();
    private static final Long NOT_SO_LONG_TIME_AGO = Instant.parse("2008-12-03T10:15:30.00Z").toEpochMilli();

    /*
     * Comparison table:
     * ETag 1       ETag 2      Strong Comparison   Weak Comparison
     * W/"1"        W/"1"       no match            match
     * W/"1"        W/"2"       no match            no match
     * W/"1"        "1"         no match            match
     * "1"          "1"         match               match
     */
    @Test
    void testWeakComparison() {
        assertThat(weakComparison(ImmutableList.of(new ETag("1", true)), new ETag("1", true)))
                .isTrue();
        assertThat(weakComparison(ImmutableList.of(new ETag("1", true)), new ETag("2", true)))
                .isFalse();
        assertThat(weakComparison(ImmutableList.of(new ETag("1", false)), new ETag("1", true)))
                .isTrue();
        assertThat(weakComparison(ImmutableList.of(new ETag("1", false)), new ETag("1", false)))
                .isTrue();
    }

    @Test
    void testStrongComparison() {
        assertThat(strongComparison(ImmutableList.of(new ETag("1", true)), new ETag("1", true)))
                .isFalse();
        assertThat(strongComparison(ImmutableList.of(new ETag("1", true)), new ETag("2", true)))
                .isFalse();
        assertThat(strongComparison(ImmutableList.of(new ETag("1", false)), new ETag("1", true)))
                .isFalse();
        assertThat(strongComparison(ImmutableList.of(new ETag("1", false)), new ETag("1", false)))
                .isTrue();
    }

    @Test
    void testIfMatch() {
        assertThat(ConditionalRequestUtil.ifMatch("W/\"1\"", new ETag("1", true)))
                .isSameAs(ETagResponse.PERFORM_METHOD);
        assertThat(ConditionalRequestUtil.ifMatch("*", new ETag("1", true)))
                .isSameAs(ETagResponse.PERFORM_METHOD);
        assertThat(ConditionalRequestUtil.ifMatch("*", null))
                .isSameAs(ETagResponse.SKIP_METHOD_PRECONDITION_FAILED);
        assertThat(ConditionalRequestUtil.ifMatch("\"1\"", new ETag("1", true)))
                .isSameAs(ETagResponse.PERFORM_METHOD);
        assertThat(ConditionalRequestUtil.ifMatch("\"1\"", new ETag("1", false)))
                .isSameAs(ETagResponse.SKIP_METHOD_PRECONDITION_FAILED);
    }

    @Test
    void testIfNoneMatch() {
        assertThat(ConditionalRequestUtil.ifNoneMatch("W/\"1\"", new ETag("1", true)))
                .isSameAs(ETagResponse.SKIP_METHOD_NOT_MODIFIED);
        assertThat(ConditionalRequestUtil.ifNoneMatch("*", new ETag("1", true)))
                .isSameAs(ETagResponse.SKIP_METHOD_NOT_MODIFIED);
        assertThat(ConditionalRequestUtil.ifNoneMatch("*", null))
                .isSameAs(ETagResponse.PERFORM_METHOD);
        assertThat(ConditionalRequestUtil.ifNoneMatch("\"1\"", new ETag("1", true)))
                .isSameAs(ETagResponse.SKIP_METHOD_NOT_MODIFIED);
        assertThat(ConditionalRequestUtil.ifNoneMatch("\"1\"", new ETag("1", false)))
                .isSameAs(ETagResponse.SKIP_METHOD_NOT_MODIFIED);
    }

    /**
     * Make sure that if-none-match always trumps if-modified-since.
     */
    @Test
    void testIfNoneMatchIfModifiedSinceEvaluationOrderConditionalRequest() {
        final RequestHeaders reqHeaders =
                RequestHeaders.builder(HttpMethod.GET, "/")
                              .add(IF_NONE_MATCH, "\"foobar\"")
                              .addTimeMillis(IF_MODIFIED_SINCE, LONG_TIME_AGO)
                              .build();
        assertThat(conditionalRequest(reqHeaders, new ETag("foobar", true), NOT_SO_LONG_TIME_AGO))
                .isSameAs(ETagResponse.SKIP_METHOD_NOT_MODIFIED);
        assertThat(conditionalRequest(reqHeaders, new ETag("foo", true), LONG_TIME_AGO))
                .isSameAs(ETagResponse.PERFORM_METHOD);

        final RequestHeaders reqHeaders2 =
                RequestHeaders.builder(HttpMethod.GET, "/")
                              .addTimeMillis(IF_MODIFIED_SINCE, LONG_TIME_AGO)
                              .build();
        assertThat(conditionalRequest(reqHeaders2, new ETag("foobar", true), NOT_SO_LONG_TIME_AGO))
                .isSameAs(ETagResponse.PERFORM_METHOD);
        assertThat(conditionalRequest(reqHeaders2, new ETag("foo", true), LONG_TIME_AGO))
                .isSameAs(ETagResponse.SKIP_METHOD_NOT_MODIFIED);
    }

    /**
     * I'm not sure about the evaluation order. They are really not meant to be used for even
     * the same methods, even less in the same request, so this is the less well defined part of
     * the RFC. They do however both state that the method MUST NOT be performed when they respectively
     * evaluate to false (SKIP_METHOD_XXXXX).
     */
    @Test
    void testIfNoneMatchIfMatchEvaluationOrderConditionalRequest() {
        final RequestHeaders reqHeaders =
                RequestHeaders.builder(HttpMethod.GET, "/")
                              .add(IF_NONE_MATCH, "\"foobar\"")
                              .add(IF_MATCH, "\"foobar\"")
                              .addTimeMillis(IF_MODIFIED_SINCE, LONG_TIME_AGO)
                              .build();

        assertThat(conditionalRequest(reqHeaders, new ETag("foobar", true), NOT_SO_LONG_TIME_AGO))
                .isSameAs(ETagResponse.SKIP_METHOD_NOT_MODIFIED);
        assertThat(conditionalRequest(reqHeaders, new ETag("foo", true), LONG_TIME_AGO))
                .isSameAs(ETagResponse.PERFORM_METHOD);
    }
}
