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

package com.linecorp.armeria.server.http;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Pattern;

import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.SessionOption;
import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.client.http.HttpClientFactory;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;

public abstract class WebAppContainerTest extends AbstractServerTest {

    private static final Pattern CR_OR_LF = Pattern.compile("[\\r\\n]");

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        sb.port(0, SessionProtocol.HTTP);
        sb.port(0, SessionProtocol.HTTPS);
        SelfSignedCertificate certificate = new SelfSignedCertificate();
        sb.sslContext(SessionProtocol.HTTPS, certificate.certificate(), certificate.privateKey());
    }

    @Test
    public void testJsp() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/jsp/index.jsp")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue(),
                           startsWith("text/html"));
                final String actualContent = CR_OR_LF.matcher(EntityUtils.toString(res.getEntity()))
                                                     .replaceAll("");
                assertThat(actualContent, is(
                        "<html><body>" +
                        "<p>Hello, Armerian World!</p>" +
                        "<p>Have you heard about the class 'io.netty.buffer.ByteBuf'?</p>" +
                        "<p>Context path: </p>" + // ROOT context path
                        "<p>Request URI: /index.jsp</p>" +
                        "<p>Scheme: http</p>" +
                        "</body></html>"));
            }
        }
    }

    @Test
    public void testJapanesePath() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/jsp/日本語/index.jsp")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue(),
                           startsWith("text/html"));
                final String actualContent = CR_OR_LF.matcher(EntityUtils.toString(res.getEntity()))
                                                     .replaceAll("");
                assertThat(actualContent, is(
                        "<html><body>" +
                        "<p>Hello, Armerian World!</p>" +
                        "<p>Have you heard about the class 'io.netty.buffer.ByteBuf'?</p>" +
                        "<p>Context path: </p>" + // ROOT context path
                        "<p>Request URI: /%E6%97%A5%E6%9C%AC%E8%AA%9E/index.jsp</p>" +
                        "<p>Servlet Path: /日本語/index.jsp</p>" +
                        "</body></html>"));
            }
        }
    }

    @Test
    public void testHttps() throws Exception {
        ClientFactory clientFactory =
                new HttpClientFactory(SessionOptions.of(
                        SessionOption.TRUST_MANAGER_FACTORY.newValue(InsecureTrustManagerFactory.INSTANCE)));
        HttpClient client = clientFactory.newClient("none+" + httpsUri("/"), HttpClient.class);
        AggregatedHttpMessage response = client.get("/jsp/index.jsp").aggregate().get();
        final String actualContent = CR_OR_LF.matcher(response.content().toStringUtf8())
                                             .replaceAll("");
        assertThat(actualContent, is(
                "<html><body>" +
                "<p>Hello, Armerian World!</p>" +
                "<p>Have you heard about the class 'io.netty.buffer.ByteBuf'?</p>" +
                "<p>Context path: </p>" + // ROOT context path
                "<p>Request URI: /index.jsp</p>" +
                "<p>Scheme: https</p>" +
                "</body></html>"));
    }

    @Test
    public void testGetQueryString() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(
                    new HttpGet(uri("/jsp/query_string.jsp?foo=%31&bar=%32")))) {

                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue(),
                           startsWith("text/html"));
                final String actualContent = CR_OR_LF.matcher(EntityUtils.toString(res.getEntity()))
                                                     .replaceAll("");
                assertThat(actualContent, is(
                        "<html><body>" +
                        "<p>foo is 1</p>" +
                        "<p>bar is 2</p>" +
                        "</body></html>"));
            }
        }
    }

    @Test
    public void testPostQueryString() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost post = new HttpPost(uri("/jsp/query_string.jsp?foo=3"));
            post.setEntity(new UrlEncodedFormEntity(
                    Collections.singletonList(new BasicNameValuePair("bar", "4")), StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(post)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue(),
                           startsWith("text/html"));
                final String actualContent = CR_OR_LF.matcher(EntityUtils.toString(res.getEntity()))
                                                     .replaceAll("");
                assertThat(actualContent, is(
                        "<html><body>" +
                        "<p>foo is 3</p>" +
                        "<p>bar is 4</p>" +
                        "</body></html>"));
            }
        }
    }

    @Test
    public void testAddressesAndPorts_127001() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/jsp/addrs_and_ports.jsp")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue(),
                           startsWith("text/html"));
                final String actualContent = CR_OR_LF.matcher(EntityUtils.toString(res.getEntity()))
                                                     .replaceAll("");

                assertTrue(actualContent, actualContent.matches(
                        "<html><body>" +
                        "<p>RemoteAddr: 127\\.0\\.0\\.1</p>" +
                        "<p>RemoteHost: 127\\.0\\.0\\.1</p>" +
                        "<p>RemotePort: [1-9][0-9]+</p>" +
                        "<p>LocalAddr: (?!null)[^<]+</p>" +
                        "<p>LocalName: " + server().defaultHostname() + "</p>" +
                        "<p>LocalPort: " + server().activePort().get().localAddress().getPort() + "</p>" +
                        "<p>ServerName: 127\\.0\\.0\\.1</p>" +
                        "<p>ServerPort: " + server().activePort().get().localAddress().getPort() + "</p>" +
                        "</body></html>"));
            }
        }
    }

    @Test
    public void testAddressesAndPorts_localhost() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpGet request = new HttpGet(uri("/jsp/addrs_and_ports.jsp"));
            request.setHeader("Host", "localhost:1111");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                assertThat(res.getFirstHeader(HttpHeaderNames.CONTENT_TYPE.toString()).getValue(),
                           startsWith("text/html"));
                final String actualContent = CR_OR_LF.matcher(EntityUtils.toString(res.getEntity()))
                                                     .replaceAll("");

                assertTrue(actualContent, actualContent.matches(
                        "<html><body>" +
                        "<p>RemoteAddr: 127\\.0\\.0\\.1</p>" +
                        "<p>RemoteHost: 127\\.0\\.0\\.1</p>" +
                        "<p>RemotePort: [1-9][0-9]+</p>" +
                        "<p>LocalAddr: (?!null)[^<]+</p>" +
                        "<p>LocalName: " + server().defaultHostname() + "</p>" +
                        "<p>LocalPort: " + server().activePort().get().localAddress().getPort() + "</p>" +
                        "<p>ServerName: localhost</p>" +
                        "<p>ServerPort: 1111</p>" +
                        "</body></html>"));
            }
        }
    }
}
