/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpServerDefaultHeadersTest {
    @RegisterExtension
    static final ServerExtension serverWithDefaults = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithoutServerHeader = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.disableServerHeader();
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithoutDateHeader = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.disableDateHeader();
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithServerNameOverridden = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
            sb.decorator((delegate, ctx, req) -> {
                ctx.addAdditionalResponseHeader(HttpHeaderNames.SERVER, "name-set-by-user");
                return delegate.serve(ctx, req);
            });
        }
    };

    @Test
    void testServerNameAndDateHeaderIncludedByDefault() {
        final WebClient client = WebClient.of(serverWithDefaults.httpUri());
        final AggregatedHttpResponse res = client.get("/").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        assertThat(res.headers().names()).contains(HttpHeaderNames.SERVER);
        assertThat(res.headers().get(HttpHeaderNames.SERVER)).contains("Armeria");

        assertThat(res.headers().names()).contains(HttpHeaderNames.DATE);
        assertThat(isValidDateTimeFormat(res.headers().get(HttpHeaderNames.DATE))).isTrue();
    }

    @Test
    void testServerNameHeaderShouldBeExcludedByOption() {
        final WebClient client = WebClient.of(serverWithoutServerHeader.httpUri());
        final AggregatedHttpResponse res = client.get("/").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        assertThat(res.headers().names()).doesNotContain(HttpHeaderNames.SERVER);

        assertThat(res.headers().names()).contains(HttpHeaderNames.DATE);
        assertThat(isValidDateTimeFormat(res.headers().get(HttpHeaderNames.DATE))).isTrue();
    }

    @Test
    void testDateHeaderShouldBeExcludedByOption() {
        final WebClient client = WebClient.of(serverWithoutDateHeader.httpUri());
        final AggregatedHttpResponse res = client.get("/").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        assertThat(res.headers().names()).contains(HttpHeaderNames.SERVER);
        assertThat(res.headers().get(HttpHeaderNames.SERVER)).contains("Armeria");

        assertThat(res.headers().names()).doesNotContain(HttpHeaderNames.DATE);
    }

    @Test
    void testServerNameHeaderOverride() {
        final WebClient client = WebClient.of(serverWithServerNameOverridden.httpUri());
        final AggregatedHttpResponse res = client.get("/").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        assertThat(res.headers().names()).contains(HttpHeaderNames.SERVER);
        assertThat(res.headers().get(HttpHeaderNames.SERVER)).isEqualTo("name-set-by-user");
    }

    private static boolean isValidDateTimeFormat(String dateTimeString) {
        try {
            final ZonedDateTime parsedDateTime =
                    ZonedDateTime.parse(dateTimeString, DateTimeFormatter.RFC_1123_DATE_TIME);
            final String parsedDateTimeString = parsedDateTime.format(DateTimeFormatter.RFC_1123_DATE_TIME);
            return parsedDateTimeString.equals(dateTimeString);
        } catch (DateTimeException e) {
            return false;
        }
    }
}
