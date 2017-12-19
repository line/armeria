/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.server.file;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.handler.codec.DateFormatter;

public class HttpFileServiceTest {

    private static final String baseResourceDir =
            HttpFileServiceTest.class.getPackage().getName().replace('.', '/') + '/';
    private static final File tmpDir;

    private static final Server server;
    private static int httpPort;

    static {
        try {
            tmpDir = Files.createTempDirectory("armeria-test.").toFile();
        } catch (Exception e) {
            throw new Error(e);
        }

        final ServerBuilder sb = new ServerBuilder();

        try {
            sb.serviceUnder(
                    "/fs/",
                    HttpFileService.forFileSystem(tmpDir.toPath()).decorate(LoggingService.newDecorator()));

            sb.serviceUnder(
                    "/compressed/",
                    HttpFileServiceBuilder.forClassPath(baseResourceDir + "foo")
                                          .serveCompressedFiles(true)
                                          .build());

            sb.serviceUnder(
                    "/",
                    HttpFileService.forClassPath(baseResourceDir + "foo")
                                   .orElse(HttpFileService.forClassPath(baseResourceDir + "bar"))
                                   .decorate(LoggingService.newDecorator()));
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();

        httpPort = server.activePorts().values().stream()
                         .filter(p -> p.protocol() == HTTP).findAny().get().localAddress().getPort();
    }

    @AfterClass
    public static void destroy() throws Exception {
        server.stop();

        // Delete the temporary files created for testing against the real file system.
        Files.walkFileTree(tmpDir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test
    public void testClassPathGet() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final String lastModified;
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/foo.txt")))) {
                lastModified = assert200Ok(res, "text/plain", "foo");
            }

            // Test if the 'If-Modified-Since' header works as expected.
            HttpUriRequest req = new HttpGet(newUri("/foo.txt"));
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());
            req.setHeader(HttpHeaders.CONNECTION, "close");

            try (CloseableHttpResponse res = hc.execute(req)) {
                assert304NotModified(res, lastModified);
            }
        }
    }

    @Test
    public void testClassPathOrElseGet() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal();
             CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/bar.txt")))) {
            assert200Ok(res, "text/plain", "bar");
        }
    }

    @Test
    public void testIndexHtml() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/")))) {
                assert200Ok(res, "text/html", "<html><body></body></html>");
            }
        }
    }

    @Test
    public void testUnknownMediaType() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal();
             CloseableHttpResponse res = hc.execute(new HttpGet(newUri("/bar.unknown")))) {
            final String lastModified = assert200Ok(res, null, "Unknown Media Type");

            HttpUriRequest req = new HttpGet(newUri("/bar.unknown"));
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());
            req.setHeader(HttpHeaders.CONNECTION, "close");

            try (CloseableHttpResponse resCached = hc.execute(req)) {
                assert304NotModified(resCached, lastModified);
            }
        }
    }

    @Test
    public void testGetPreCompressedSupportsNone() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpGet request = new HttpGet(newUri("/compressed/foo.txt"));
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(res.getFirstHeader("Content-Encoding"), is(nullValue()));
                assertThat(res.getFirstHeader("Content-Type").getValue(), is("text/plain; charset=utf-8"));
                final byte[] content = ByteStreams.toByteArray(res.getEntity().getContent());
                assertThat(new String(content, StandardCharsets.UTF_8), is("foo"));
            }
        }
    }

    @Test
    public void testGetPreCompressedSupportsGzip() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpGet request = new HttpGet(newUri("/compressed/foo.txt"));
            request.setHeader("Accept-Encoding", "gzip");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(res.getFirstHeader("Content-Encoding").getValue(), is("gzip"));
                assertThat(res.getFirstHeader("Content-Type").getValue(), is("text/plain; charset=utf-8"));
                final byte[] content;
                try (GZIPInputStream unzipper = new GZIPInputStream(res.getEntity().getContent())) {
                    content = ByteStreams.toByteArray(unzipper);
                }
                assertThat(new String(content, StandardCharsets.UTF_8), is("foo"));
            }
        }
    }

    @Test
    public void testGetPreCompressedSupportsBrotli() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpGet request = new HttpGet(newUri("/compressed/foo.txt"));
            request.setHeader("Accept-Encoding", "br");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(res.getFirstHeader("Content-Encoding").getValue(), is("br"));
                assertThat(res.getFirstHeader("Content-Type").getValue(), is("text/plain; charset=utf-8"));
                // Test would be more readable and fun by decompressing like the gzip one, but since JDK doesn't
                // support brotli yet, just compare the compressed content to avoid adding a complex dependency.
                final byte[] content = ByteStreams.toByteArray(res.getEntity().getContent());
                assertThat(content,
                           is(Resources.toByteArray(Resources.getResource(
                                   baseResourceDir + "foo/foo.txt.br"))));
            }
        }
    }

    @Test
    public void testGetPreCompressedSupportsBothPrefersBrotli() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            HttpGet request = new HttpGet(newUri("/compressed/foo.txt"));
            request.setHeader("Accept-Encoding", "gzip, br");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(res.getFirstHeader("Content-Encoding").getValue(), is("br"));
                assertThat(res.getFirstHeader("Content-Type").getValue(), is("text/plain; charset=utf-8"));
                // Test would be more readable and fun by decompressing like the gzip one, but since JDK doesn't
                // support brotli yet, just compare the compressed content to avoid adding a complex dependency.
                final byte[] content = ByteStreams.toByteArray(res.getEntity().getContent());
                assertThat(content,
                           is(Resources.toByteArray(Resources.getResource(
                                   baseResourceDir + "foo/foo.txt.br"))));
            }
        }
    }

    @Test
    public void testFileSystemGet() throws Exception {
        final File barFile = new File(tmpDir, "bar.html");
        final String expectedContentA = "<html/>";
        final String expectedContentB = "<html><body/></html>";
        Files.write(barFile.toPath(), expectedContentA.getBytes(StandardCharsets.UTF_8));

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final String lastModified;
            HttpUriRequest req = new HttpGet(newUri("/fs/bar.html"));
            try (CloseableHttpResponse res = hc.execute(req)) {
                lastModified = assert200Ok(res, "text/html", expectedContentA);
            }

            // Test if the 'If-Modified-Since' header works as expected.
            req = new HttpGet(newUri("/fs/bar.html"));
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());

            try (CloseableHttpResponse res = hc.execute(req)) {
                assert304NotModified(res, lastModified);
            }

            // Test if the 'If-Modified-Since' header works as expected after the file is modified.
            req = new HttpGet(newUri("/fs/bar.html"));
            Date now = new Date();
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE, httpDate(now));

            // HTTP-date has no sub-second precision; just add a few seconds to the time.
            Files.write(barFile.toPath(), expectedContentB.getBytes(StandardCharsets.UTF_8));
            assertThat(barFile.setLastModified(now.getTime() + TimeUnit.SECONDS.toMillis(5)),
                       is(true));

            try (CloseableHttpResponse res = hc.execute(req)) {
                final String newLastModified = assert200Ok(res, "text/html", expectedContentB);

                // Ensure that the 'Last-Modified' changed.
                assertThat(newLastModified, is(not(lastModified)));
            }

            // Test if the cache detects the file removal correctly.
            final boolean deleted = barFile.delete();
            assertThat(deleted, is(true));

            req = new HttpGet(newUri("/fs/bar.html"));
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());
            req.setHeader(HttpHeaders.CONNECTION, "close");

            try (CloseableHttpResponse res = hc.execute(req)) {
                assert404NotFound(res);
            }
        }
    }

    private static String assert200Ok(
            CloseableHttpResponse res, String expectedContentType, String expectedContent) throws Exception {

        assertStatusLine(res, "HTTP/1.1 200 OK");

        // Ensure that the 'Last-Modified' header exists and is well-formed.
        final String lastModified;
        assertThat(res.containsHeader(HttpHeaders.LAST_MODIFIED), is(true));
        lastModified = res.getFirstHeader(HttpHeaders.LAST_MODIFIED).getValue();
        DateFormatter.parseHttpDate(lastModified);

        // Ensure the content and its type are correct.
        assertThat(EntityUtils.toString(res.getEntity()).trim(), is(expectedContent));

        if (expectedContentType != null) {
            assertThat(res.containsHeader(HttpHeaders.CONTENT_TYPE), is(true));
            assertThat(res.getFirstHeader(HttpHeaders.CONTENT_TYPE).getValue(),
                       startsWith(expectedContentType));
        } else {
            assertThat(res.containsHeader(HttpHeaders.CONTENT_TYPE), is(false));
        }

        return lastModified;
    }

    private static void assert304NotModified(
            CloseableHttpResponse res, String expectedLastModified) {

        assertStatusLine(res, "HTTP/1.1 304 Not Modified");

        // Ensure that the 'Last-Modified' header did not change.
        assertThat(res.getFirstHeader(HttpHeaders.LAST_MODIFIED).getValue(), is(expectedLastModified));

        // Ensure that the content does not exist.
        assertThat(res.getEntity(), is(nullValue()));
    }

    private static void assert404NotFound(CloseableHttpResponse res) {
        assertStatusLine(res, "HTTP/1.1 404 Not Found");
        // Ensure that the 'Last-Modified' header does not exist.
        assertThat(res.getFirstHeader(HttpHeaders.LAST_MODIFIED), is(nullValue()));
    }

    private static void assertStatusLine(CloseableHttpResponse res, String expectedStatusLine) {
        assertThat(res.getStatusLine().toString(), is(expectedStatusLine));
    }

    private static String currentHttpDate() {
        return httpDate(new Date());
    }

    private static String httpDate(Date date) {
        return DateFormatter.format(date);
    }

    private static String newUri(String path) {
        return "http://127.0.0.1:" + httpPort + path;
    }
}
