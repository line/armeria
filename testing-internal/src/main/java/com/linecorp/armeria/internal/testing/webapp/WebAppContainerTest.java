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

package com.linecorp.armeria.internal.testing.webapp;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * Tests a web application container {@link Service}.
 */
public abstract class WebAppContainerTest {

    protected static final Pattern CR_OR_LF = Pattern.compile("[\\r\\n]");

    /**
     * Returns the doc-base directory of the test web application.
     */
    public static File webAppRoot() {
        final URL url = WebAppContainerTest.class.getProtectionDomain().getCodeSource().getLocation();
        File f;
        try {
            f = new File(url.toURI());
        } catch (URISyntaxException ignored) {
            f = new File(url.getPath());
        }

        final File buildDir;
        if (f.isDirectory()) {
            // f is: testing-internal/build/resources/main
            buildDir = f.getParentFile().getParentFile();
        } else {
            // f is: testing-internal/build/libs/armeria-testing-internal-*.jar
            assert f.isFile();
            buildDir = f.getParentFile().getParentFile();
        }

        assert buildDir.getPath().endsWith("testing-internal" + File.separatorChar + "build") ||
               buildDir.getPath().endsWith("testing-internal" + File.separatorChar + "out"); // IntelliJ IDEA

        final File webAppRoot = new File(
                buildDir.getParentFile(), "src" + File.separatorChar + "main" + File.separatorChar + "webapp");
        assert webAppRoot.isDirectory();
        return webAppRoot;
    }

    /**
     * Returns the {@link ServerExtension} that provides the {@link Server} that serves the {@link Service}s
     * this test runs against.
     */
    protected abstract ServerExtension server();

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C", "H1", "H2" })
    public void jsp(SessionProtocol sessionProtocol) throws Exception {
        final WebClient client = WebClient.builder(server().uri(sessionProtocol))
                                          .factory(ClientFactory.insecure())
                                          .build();
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.get("/jsp/index.jsp").aggregate().join();
            assertThat(res.status()).isSameAs(HttpStatus.OK);
            assertThat(res.contentType()).isEqualTo(MediaType.HTML_UTF_8);

            final String actualContent = CR_OR_LF.matcher(res.contentUtf8()).replaceAll("");

            // Get the session protocol observed from the client side.
            final String expectedProtocol = ctxCaptor.get().log().ensureAvailable(RequestLogProperty.SESSION)
                                                     .sessionProtocol().isMultiplex() ? "HTTP/2.0" : "HTTP/1.1";
            assertThat(actualContent).isEqualTo(
                    "<html><body>" +
                    "<p>Hello, Armerian World!</p>" +
                    "<p>Have you heard about the class 'org.slf4j.Logger'?</p>" +
                    "<p>Host: 127.0.0.1:" + server().port(sessionProtocol) + "</p>" +
                    "<p>Context path: </p>" + // ROOT context path
                    "<p>Request URI: /index.jsp</p>" +
                    "<p>Scheme: " + (sessionProtocol.isTls() ? "https" : "http") + "</p>" +
                    "<p>Protocol: " + expectedProtocol + "</p>" +
                    "</body></html>");
        }
    }

    @Test
    public void japanesePath() throws Exception {
        final AggregatedHttpResponse response = server().blockingWebClient().get(
                "/jsp/" + URLEncoder.encode("日本語", "UTF-8") + "/index.jsp");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        final String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).isEqualTo(
                "<html><body>" +
                "<p>Hello, Armerian World!</p>" +
                "<p>Have you heard about the class 'org.slf4j.Logger'?</p>" +
                "<p>Context path: </p>" + // ROOT context path
                "<p>Request URI: /%E6%97%A5%E6%9C%AC%E8%AA%9E/index.jsp</p>" +
                "<p>Servlet Path: /日本語/index.jsp</p>" +
                "</body></html>");
    }

    @Test
    public void getWithQueryString() throws Exception {
        AggregatedHttpResponse response = server().blockingWebClient().get(
                "/jsp/query_string.jsp?foo=%31&bar=%32");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                       .replaceAll("");
        assertThat(actualContent).isEqualTo(
                "<html><body>" +
                "<p>foo is 1</p>" +
                "<p>bar is 2</p>" +
                "</body></html>");
        response = server().blockingWebClient().get(
                "/jsp/query_string.jsp?foo=%33&bar=%34");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).isEqualTo(
                "<html><body>" +
                "<p>foo is 3</p>" +
                "<p>bar is 4</p>" +
                "</body></html>");
    }

    @Test
    public void postWithQueryString() throws Exception {
        RequestHeaders headers = RequestHeaders.builder(
                HttpMethod.POST, "jsp/query_string.jsp?foo=3").contentType(MediaType.FORM_DATA).build();
        AggregatedHttpResponse response = server().blockingWebClient().execute(headers, "bar=4");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                       .replaceAll("");
        assertThat(actualContent).isEqualTo(
                "<html><body>" +
                "<p>foo is 3</p>" +
                "<p>bar is 4</p>" +
                "</body></html>");
        headers = RequestHeaders.builder(
                HttpMethod.POST, "jsp/query_string.jsp?foo=5").contentType(MediaType.FORM_DATA).build();
        response = server().blockingWebClient().execute(headers, "bar=6");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                       .replaceAll("");
        assertThat(actualContent).isEqualTo(
                "<html><body>" +
                "<p>foo is 5</p>" +
                "<p>bar is 6</p>" +
                "</body></html>");
    }

    @Test
    public void echoPost() throws Exception {
        final AggregatedHttpResponse response =
                server().blockingWebClient(cb -> cb.responseTimeoutMillis(0))
                        .post("/jsp/echo_post.jsp", HttpData.ofUtf8("test"));
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        final String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).isEqualTo(
                "<html><body>" +
                "<p>Check request body</p>" +
                "<p>test</p>" +
                "</body></html>");
    }

    @Test
    public void echoPostWithEmptyBody() throws Exception {
        final AggregatedHttpResponse response = server().blockingWebClient().post(
                "/jsp/echo_post.jsp", HttpData.empty());
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        final String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).isEqualTo(
                "<html><body>" +
                "<p>Check request body</p>" +
                "<p></p>" +
                "</body></html>");
    }

    @Test
    public void addressesAndPorts_127001() throws Exception {
        final AggregatedHttpResponse response = WebClient.of(server().httpUri()).blocking()
                                                         .get("/jsp/addrs_and_ports.jsp");
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        final String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).matches(
                "<html><body>" +
                "<p>RemoteAddr: 127\\.0\\.0\\.1</p>" +
                "<p>RemoteHost: 127\\.0\\.0\\.1</p>" +
                "<p>RemotePort: [1-9][0-9]+</p>" +
                "<p>LocalAddr: (?!null)[^<]+</p>" +
                "<p>LocalName: " + server().server().defaultHostname() + "</p>" +
                "<p>LocalPort: " + server().httpPort() + "</p>" +
                "<p>ServerName: 127\\.0\\.0\\.1</p>" +
                "<p>ServerPort: " + server().httpPort() + "</p>" +
                "</body></html>");
    }

    @Test
    public void addressesAndPorts_localhost() throws Exception {
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, "/jsp/addrs_and_ports.jsp", "Host",
                                                         "localhost:1111");
        final AggregatedHttpResponse response = WebClient.of(server().httpUri()).blocking().execute(headers);
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/html");
        final String actualContent = CR_OR_LF.matcher(response.contentUtf8())
                                             .replaceAll("");
        assertThat(actualContent).matches(
                "<html><body>" +
                "<p>RemoteAddr: 127\\.0\\.0\\.1</p>" +
                "<p>RemoteHost: 127\\.0\\.0\\.1</p>" +
                "<p>RemotePort: [1-9][0-9]+</p>" +
                "<p>LocalAddr: (?!null)[^<]+</p>" +
                "<p>LocalName: " + server().server().defaultHostname() + "</p>" +
                "<p>LocalPort: " + server().httpPort() + "</p>" +
                "<p>ServerName: localhost</p>" +
                "<p>ServerPort: 1111</p>" +
                "</body></html>");
    }

    @Test
    public void largeFile() throws Exception {
        testLarge("/jsp/large.txt", true /* Static content has a content-length header. */);
    }

    @Test
    public void largeResponse() throws Exception {
        testLarge("/jsp/large.jsp", false /* Dynamic content doesn't have a content-length header */);
    }

    protected void testLarge(String path, boolean requiresContentLength) throws IOException {
        final AggregatedHttpResponse response = server().blockingWebClient().get(path);
        assertThat(response.status()).isSameAs(HttpStatus.OK);
        assertThat(response.contentType().toString()).startsWith("text/plain");
        if (requiresContentLength) {
            // Check if the content-length header matches.
            assertThat(response.headers().contentLength()).isEqualTo(response.content().length());
        }

        // Check if the content contains what's expected.
        assertThat(Arrays.stream(CR_OR_LF.split(response.contentUtf8()))
                         .map(String::trim)
                         .filter(s -> !s.isEmpty())
                         .count()).isEqualTo(1024);
    }

    @ParameterizedTest
    @CsvSource({ "H1", "H2"})
    public void tlsAttrs(SessionProtocol sessionProtocol) throws Exception {
        final WebClient client = WebClient.builder(server().uri(sessionProtocol))
                                          .factory(ClientFactory.insecure())
                                          .build();

        final AggregatedHttpResponse res = client.get("/jsp/tls.jsp").aggregate().join();
        final SSLSession sslSession = server().requestContextCaptor().take().sslSession();
        final String expectedId;
        if (sslSession.getId() != null) {
            expectedId = BaseEncoding.base16().encode(sslSession.getId());
        } else {
            expectedId = "";
        }

        assertThatJson(res.contentUtf8())
                .node("sessionId").isStringEqualTo(expectedId)
                .node("cipherSuite").isStringEqualTo(sslSession.getCipherSuite())
                .node("keySize").matches(greaterThan(BigDecimal.ZERO))
                .node("hasPeerCerts").isEqualTo(false);
    }
}
