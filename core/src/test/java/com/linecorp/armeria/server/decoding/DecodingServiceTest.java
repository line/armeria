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

package com.linecorp.armeria.server.decoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DecodingServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService httpService = (ctx, req) -> HttpResponse.from(
                    req.aggregate()
                       .thenApply(aggregated -> {
                           final ResponseHeaders responseHeaders =
                                   ResponseHeaders.of(HttpStatus.OK,
                                                      HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
                           return HttpResponse.of(responseHeaders,
                                                  HttpData.ofUtf8("Hello " + aggregated.contentUtf8() + '!'));
                       }));
            sb.decorator("/decodeTest", DecodingService.newDecorator());
            sb.service("/decodeTest", httpService);

            sb.decoratorUnder("/", (delegate, ctx, req) -> {
                return delegate.serve(ctx, req);
            });
        }
    };

    @Test
    void decodingGzippedPayloadFromClient() throws IOException {

        final WebClient client = WebClient.builder(server.httpUri()).build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/decodeTest",
                                                         HttpHeaderNames.CONTENT_ENCODING, "gzip");
        final ByteArrayOutputStream encodedStream = new ByteArrayOutputStream();
        final DeflaterOutputStream encodingStream = new GZIPOutputStream(encodedStream, true);

        final byte[] testByteArray = "Armeria Gzip Test".getBytes(StandardCharsets.UTF_8);
        encodingStream.write(testByteArray);
        encodingStream.flush();

        assertThat(client.execute(headers, HttpData.wrap(encodedStream.toByteArray())).aggregate().join()
                         .contentUtf8()).isEqualTo("Hello Armeria Gzip Test!");
    }

    @Test
    void decodingDeflatedPayloadFromClient() throws IOException {

        final WebClient client = WebClient.builder(server.httpUri()).build();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/decodeTest",
                                                         HttpHeaderNames.CONTENT_ENCODING, "deflate");
        final ByteArrayOutputStream encodedStream = new ByteArrayOutputStream();
        final DeflaterOutputStream encodingStream = new DeflaterOutputStream(encodedStream, true);

        final byte[] testByteArray = "Armeria Deflated Test".getBytes(StandardCharsets.UTF_8);
        encodingStream.write(testByteArray);
        encodingStream.flush();

        assertThat(client.execute(headers, HttpData.wrap(encodedStream.toByteArray())).aggregate().join()
                         .contentUtf8()).isEqualTo("Hello Armeria Deflated Test!");
    }
}
