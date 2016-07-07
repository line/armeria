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
package com.linecorp.armeria.server.http.file;

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

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;

import io.netty.handler.codec.http.HttpHeaderDateFormat;

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
                    HttpFileService.forFileSystem(tmpDir.toPath()).decorate(LoggingService::new));

            sb.serviceUnder(
                    "/",
                    HttpFileService.forClassPath(baseResourceDir + "foo")
                                   .orElse(HttpFileService.forClassPath(baseResourceDir + "bar"))
                                   .decorate(LoggingService::new));
        } catch (Exception e) {
            throw new Error(e);
        }
        server = sb.build();
    }

    @BeforeClass
    public static void init() throws Exception {
        server.start().get();

        httpPort = server.activePorts().values().stream()
                .filter(p -> p.protocol() == SessionProtocol.HTTP).findAny().get().localAddress().getPort();
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
                assert304NotModified(res, lastModified, "text/plain");
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
                assert304NotModified(res, lastModified, "text/html");
            }

            // Test if the 'If-Modified-Since' header works as expected after the file is modified.
            req = new HttpGet(newUri("/fs/bar.html"));
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());

            // HTTP-date has no sub-second precision; wait until the current second changes.
            Thread.sleep(1000);

            Files.write(barFile.toPath(), expectedContentB.getBytes(StandardCharsets.UTF_8));

            try (CloseableHttpResponse res = hc.execute(req)) {
                final String newLastModified = assert200Ok(res, "text/html", expectedContentB);

                // Ensure that the 'Last-Modified' header did not change.
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
        HttpHeaderDateFormat.get().parse(lastModified);

        // Ensure the content and its type are correct.
        assertThat(EntityUtils.toString(res.getEntity()), is(expectedContent));

        assertThat(res.containsHeader(HttpHeaders.CONTENT_TYPE), is(true));
        assertThat(res.getFirstHeader(HttpHeaders.CONTENT_TYPE).getValue(), startsWith(expectedContentType));

        return lastModified;
    }

    private static void assert304NotModified(
            CloseableHttpResponse res, String expectedLastModified, String expectedContentType) {

        assertStatusLine(res, "HTTP/1.1 304 Not Modified");

        // Ensure that the 'Last-Modified' header did not change.
        assertThat(res.getFirstHeader(HttpHeaders.LAST_MODIFIED).getValue(), is(expectedLastModified));

        // Ensure that the content does not exist but its type does.
        assertThat(res.getEntity(), is(nullValue()));

        assertThat(res.containsHeader(HttpHeaders.CONTENT_TYPE), is(true));
        assertThat(res.getFirstHeader(HttpHeaders.CONTENT_TYPE).getValue(), startsWith(expectedContentType));
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
        return HttpHeaderDateFormat.get().format(new Date());
    }

    private static String newUri(String path) {
        return "http://127.0.0.1:" + httpPort + path;
    }
}
