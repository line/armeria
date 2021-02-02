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

package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.ContentPreviewingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SpringServerMultipartTest {

    @LocalServerPort
    int port;

    WebClient client;

    @BeforeEach
    void setUp() {
        final ContentPreviewerFactory factory = ContentPreviewerFactory.builder()
                                                                       .maxLength(Integer.MAX_VALUE)
                                                                       .text((req, headers) -> true)
                                                                       .build();
        client = WebClient.builder("http://127.0.0.1:" + port)
                          .responseTimeoutMillis(0)
                          .decorator(ContentPreviewingClient.newDecorator(factory))
                          .decorator(LoggingClient.newDecorator())
                          .factory(ClientFactory.builder()
                                                .idleTimeoutMillis(0)
                                                .build())
                          .build();
    }

    @Test
    void multipart() {
        final HttpHeaders userHeaders =
                HttpHeaders.builder()
                           .contentType(MediaType.PLAIN_TEXT_UTF_8)
                           .contentDisposition(ContentDisposition.builder("form-data")
                                                                 .name("user")
                                                                 .build())
                           .contentType(MediaType.PLAIN_TEXT_UTF_8)
                           .build();
        final BodyPart user = BodyPart.builder()
                                      .headers(userHeaders)
                                      .content("Meri Kim")
                                      .build();

        final HttpHeaders orgHeaders = HttpHeaders.builder()
                                                  .contentDisposition(
                                                          ContentDisposition.builder("form-data")
                                                                            .name("org")
                                                                            .build())
                                                  .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                                  .build();
        final BodyPart org = BodyPart.builder()
                                     .headers(orgHeaders)
                                     .content("LINE")
                                     .build();
        final Multipart multiPart = Multipart.of(user, org);

        final HttpRequest request = multiPart.toHttpRequest("/multipart/text");

        final AggregatedHttpResponse response = client.execute(request).aggregate().join();
        assertThat(response.content().toStringUtf8()).isEqualTo("LINE/Meri Kim");
    }

    @Test
    void fileUpload() {
        final HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_DISPOSITION,
                                                   ContentDisposition.of("form-data", "file", "test.txt"));
        final BodyPart filePart = BodyPart.builder()
                                          .headers(headers)
                                          .content("Hello!")
                                          .build();

        final HttpRequest request = Multipart.of(filePart).toHttpRequest("/multipart/file");

        final AggregatedHttpResponse response = client.execute(request).aggregate().join();
        assertThat(response.content().toStringUtf8()).isEqualTo("test.txt/file/Hello!");
    }

    @Test
    void fileUploadWithRequestHeaders() {
        final HttpHeaders headers = HttpHeaders.of(HttpHeaderNames.CONTENT_DISPOSITION,
                                                   ContentDisposition.of("form-data", "file", "test.txt"));
        final BodyPart filePart = BodyPart.builder()
                                          .headers(headers)
                                          .content("Hello!")
                                          .build();

        final RequestHeaders requestHeaders = RequestHeaders.of(HttpMethod.POST, "/multipart/file");
        final HttpRequest request = Multipart.of(filePart).toHttpRequest(requestHeaders);

        final AggregatedHttpResponse response = client.execute(request).aggregate().join();
        assertThat(response.content().toStringUtf8()).isEqualTo("test.txt/file/Hello!");
    }
}
