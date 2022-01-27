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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOError;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.OsType;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.PathAndQuery;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.DateFormatter;

class FileServiceTest {

    private static final Logger logger = LoggerFactory.getLogger(FileServiceTest.class);

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Pattern ETAG_PATTERN = Pattern.compile("^\"[^\"]+\"$");

    private static final String baseResourceDir =
            FileServiceTest.class.getPackage().getName().replace('.', '/') + '/';

    @TempDir
    static Path tmpDir;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {

            final ClassLoader classLoader = getClass().getClassLoader();

            sb.serviceUnder(
                    "/cached/fs/",
                    FileService.builder(tmpDir)
                               .autoIndex(true)
                               .build());

            sb.serviceUnder(
                    "/uncached/fs/",
                    FileService.builder(tmpDir)
                               .maxCacheEntries(0)
                               .autoIndex(true)
                               .build());

            sb.serviceUnder(
                    "/cached/compressed/",
                    FileService.builder(classLoader, baseResourceDir + "foo")
                               .serveCompressedFiles(true)
                               .build());
            sb.serviceUnder(
                    "/uncached/compressed/",
                    FileService.builder(classLoader, baseResourceDir + "foo")
                               .serveCompressedFiles(true)
                               .maxCacheEntries(0)
                               .build());

            sb.serviceUnder(
                    "/cached/compressed/decompress",
                    FileService.builder(classLoader, baseResourceDir + "baz")
                               .serveCompressedFiles(true)
                               .autoDecompress(true)
                               .build());
            sb.serviceUnder(
                    "/uncached/compressed/decompress",
                    FileService.builder(classLoader, baseResourceDir + "baz")
                               .serveCompressedFiles(true)
                               .autoDecompress(true)
                               .maxCacheEntries(0)
                               .build());

            sb.serviceUnder(
                    "/cached/uncompressed/decompress",
                    FileService.builder(classLoader, baseResourceDir + "qux")
                               .serveCompressedFiles(true)
                               .autoDecompress(true)
                               .build());
            sb.serviceUnder(
                    "/uncached/uncompressed/decompress",
                    FileService.builder(classLoader, baseResourceDir + "qux")
                               .serveCompressedFiles(true)
                               .autoDecompress(true)
                               .maxCacheEntries(0)
                               .build());

            sb.serviceUnder(
                    "/cached/classes/",
                    FileService.of(classLoader, "/"));
            sb.serviceUnder(
                    "/uncached/classes/",
                    FileService.builder(classLoader, "/")
                               .maxCacheEntries(0)
                               .build());

            sb.serviceUnder(
                    "/cached/by-entry/classes/",
                    FileService.builder(classLoader, "/")
                               .entryCacheSpec("maximumSize=512")
                               .build());
            sb.serviceUnder(
                    "/uncached/by-entry/classes/",
                    FileService.builder(classLoader, "/")
                               .entryCacheSpec("off")
                               .build());

            sb.serviceUnder(
                    "/cached/",
                    FileService.of(classLoader, baseResourceDir + "foo")
                               .orElse(FileService.of(classLoader, baseResourceDir + "bar")));
            sb.serviceUnder(
                    "/uncached/",
                    FileService.builder(classLoader, baseResourceDir + "foo")
                               .maxCacheEntries(0)
                               .build()
                               .orElse(FileService.builder(classLoader, baseResourceDir + "bar")
                                                  .maxCacheEntries(0)
                                                  .build()));

            sb.decorator(LoggingService.newDecorator());
        }
    };

    @AfterAll
    static void stopSynchronously() {
        if (SystemInfo.osType() == OsType.WINDOWS) {
            // Shut down the server completely so that no files
            // are open before deleting the temporary directory.
            server.stop().join();
        }
    }

    @BeforeEach
    void setUp() {
        PathAndQuery.clearCachedPaths();
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testClassPathGet(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final String lastModified;
            final String etag;
            try (CloseableHttpResponse res = hc.execute(new HttpGet(baseUri + "/foo.txt"))) {
                assert200Ok(res, "text/plain", "foo");
                lastModified = header(res, HttpHeaders.LAST_MODIFIED);
                etag = header(res, HttpHeaders.ETAG);
            }

            assert304NotModified(hc, baseUri, "/foo.txt", etag, lastModified);

            // Confirm file service paths are cached when cache is enabled.
            if (baseUri.contains("/cached")) {
                assertThat(PathAndQuery.cachedPaths()).contains("/cached/foo.txt");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testClassPathGetUtf8(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(baseUri + "/%C2%A2.txt"))) {
                assert200Ok(res, "text/plain", "¢");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testClassPathGetFromModule(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Read a class from a JDK module (java.base).
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(baseUri + "/classes/java/lang/Object.class"))) {
                assert200Ok(res, null, content -> assertThat(content).isNotEmpty());
            }
            // Read a class from a JDK module (java.base).
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(baseUri + "/by-entry/classes/java/lang/Object.class"))) {
                assert200Ok(res, null, content -> assertThat(content).isNotEmpty());
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testClassPathGetFromJar(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Read a class from a third-party library JAR.
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(baseUri + "/classes/io/netty/util/NetUtil.class"))) {
                assert200Ok(res, null, content -> assertThat(content).isNotEmpty());
            }
            // Read a class from a third-party library JAR.
            try (CloseableHttpResponse res =
                         hc.execute(new HttpGet(baseUri + "/by-entry/classes/io/netty/util/NetUtil.class"))) {
                assert200Ok(res, null, content -> assertThat(content).isNotEmpty());
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testClassPathOrElseGet(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal();
             CloseableHttpResponse res = hc.execute(new HttpGet(baseUri + "/bar.txt"))) {
            assert200Ok(res, "text/plain", "bar");
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testIndexHtml(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(baseUri + '/'))) {
                assert200Ok(res, "text/html", "<html><body></body></html>");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testAutoIndex(String baseUri) throws Exception {
        final Path rootDir = tmpDir.resolve("auto_index");
        final Path childFile = rootDir.resolve("child_file");
        final Path childDir = rootDir.resolve("child_dir");
        final Path grandchildFile = childDir.resolve("grandchild_file");
        final Path emptyChildDir = rootDir.resolve("empty_child_dir");
        final Path childDirWithCustomIndex = rootDir.resolve("child_dir_with_custom_index");
        final Path customIndexFile = childDirWithCustomIndex.resolve("index.html");

        Files.createDirectories(childDir);
        Files.createDirectories(emptyChildDir);
        Files.createDirectories(childDirWithCustomIndex);

        writeFile(childFile, "child_file");
        writeFile(grandchildFile, "grandchild_file");
        writeFile(customIndexFile, "custom_index_file");

        final String basePath = new URI(baseUri).getPath();

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Ensure auto-redirect without query works as expected.
            HttpUriRequest req = new HttpGet(baseUri + "/fs/auto_index");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertStatusLine(res, "HTTP/1.1 307 Temporary Redirect");
                assertThat(header(res, "location")).isEqualTo(basePath + "/fs/auto_index/");
            }

            // Ensure auto-redirect with query works as expected.
            req = new HttpGet(baseUri + "/fs/auto_index?foobar=1");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertStatusLine(res, "HTTP/1.1 307 Temporary Redirect");
                assertThat(header(res, "location")).isEqualTo(basePath + "/fs/auto_index/?foobar=1");
            }

            // Ensure directory listing works as expected.
            ImmutableList.of("/fs/auto_index/", "/fs/auto_index/?foobar=1").forEach(path -> {
                try (CloseableHttpResponse res = hc.execute(new HttpGet(baseUri + path))) {
                    assertStatusLine(res, "HTTP/1.1 200 OK");
                    final String content = contentString(res);
                    assertThat(content).contains(
                            "Directory listing: " + basePath + "/fs/auto_index/",
                            "4 file(s) total",
                            "<a href=\"../\">../</a>",
                            "<a href=\"child_dir/\">child_dir/</a>",
                            "<a href=\"child_file\">child_file</a>",
                            "<a href=\"child_dir_with_custom_index/\">child_dir_with_custom_index/</a>",
                            "<a href=\"empty_child_dir/\">empty_child_dir/</a>");
                } catch (IOException e) {
                    throw new IOError(e);
                }
            });

            // Ensure directory listing on an empty directory works as expected.
            req = new HttpGet(baseUri + "/fs/auto_index/empty_child_dir/");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertStatusLine(res, "HTTP/1.1 200 OK");
                final String content = contentString(res);
                assertThat(content)
                        .contains("Directory listing: " + basePath + "/fs/auto_index/empty_child_dir/")
                        .contains("0 file(s) total")
                        .contains("<a href=\"../\">../</a>");
            }

            // Ensure directory listing on an empty directory works as expected,
            // even with query parameters.
            req = new HttpGet(baseUri + "/fs/auto_index/empty_child_dir/?foo=1");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertStatusLine(res, "HTTP/1.1 200 OK");
                final String content = contentString(res);
                assertThat(content)
                        .contains("Directory listing: " + basePath + "/fs/auto_index/empty_child_dir/")
                        .contains("0 file(s) total")
                        .contains("<a href=\"../\">../</a>");
            }

            // Ensure custom index.html takes precedence over auto-generated directory listing.
            req = new HttpGet(baseUri + "/fs/auto_index/child_dir_with_custom_index/");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertStatusLine(res, "HTTP/1.1 200 OK");
                assertThat(contentString(res)).isEqualTo("custom_index_file");
            }

            // Ensure custom index.html takes precedence over auto-generated directory listing,
            // even with query parameters.
            req = new HttpGet(baseUri + "/fs/auto_index/child_dir_with_custom_index/?foo=1");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertStatusLine(res, "HTTP/1.1 200 OK");
                assertThat(contentString(res)).isEqualTo("custom_index_file");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testUnknownMediaType(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal();
             CloseableHttpResponse res = hc.execute(new HttpGet(baseUri + "/bar.unknown"))) {
            assert200Ok(res, null, "Unknown Media Type");
            final String lastModified = header(res, HttpHeaders.LAST_MODIFIED);
            final String etag = header(res, HttpHeaders.ETAG);
            assert304NotModified(hc, baseUri, "/bar.unknown", etag, lastModified);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testGetPreCompressedSupportsNone(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet request = new HttpGet(baseUri + "/compressed/foo.txt");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(res.getFirstHeader("Content-Encoding")).isNull();
                assertThat(headerOrNull(res, "Content-Type")).isEqualTo(
                        "text/plain; charset=utf-8");
                final byte[] content = content(res);
                assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("foo");

                // Confirm path not cached when cache disabled.
                assertThat(PathAndQuery.cachedPaths())
                        .doesNotContain("/compressed/foo.txt");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testGetWithoutPreCompression(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet request = new HttpGet(baseUri + "/compressed/foo_alone.txt");
            request.setHeader("Accept-Encoding", "gzip");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(res.getFirstHeader("Content-Encoding")).isNull();
                assertThat(headerOrNull(res, "Content-Type")).isEqualTo(
                        "text/plain; charset=utf-8");
                final byte[] content = content(res);
                assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("foo_alone");

                // Confirm path not cached when cache disabled.
                assertThat(PathAndQuery.cachedPaths())
                        .doesNotContain("/compressed/foo_alone.txt");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testGetPreCompressedSupportsGzip(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet request = new HttpGet(baseUri + "/compressed/foo.txt");
            request.setHeader("Accept-Encoding", "gzip");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(headerOrNull(res, "Content-Encoding")).isEqualTo("gzip");
                assertThat(headerOrNull(res, "Content-Type")).isEqualTo(
                        "text/plain; charset=utf-8");
                final byte[] content;
                try (GZIPInputStream unzipper = new GZIPInputStream(res.getEntity().getContent())) {
                    content = ByteStreams.toByteArray(unzipper);
                }
                assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("foo");
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testGetPreCompressedSupportsBrotli(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet request = new HttpGet(baseUri + "/compressed/foo.txt");
            request.setHeader("Accept-Encoding", "br");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(headerOrNull(res, "Content-Encoding")).isEqualTo("br");
                assertThat(headerOrNull(res, "Content-Type")).isEqualTo(
                        "text/plain; charset=utf-8");
                // Test would be more readable and fun by decompressing like the gzip one, but since JDK doesn't
                // support brotli yet, just compare the compressed content to avoid adding a complex dependency.
                final byte[] content = content(res);
                assertThat(content).containsExactly(
                        Resources.toByteArray(Resources.getResource(baseResourceDir + "foo/foo.txt.br")));
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testGetPreCompressedSupportsBothPrefersBrotli(String baseUri) throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet request = new HttpGet(baseUri + "/compressed/foo.txt");
            request.setHeader("Accept-Encoding", "gzip, br");
            try (CloseableHttpResponse res = hc.execute(request)) {
                assertThat(headerOrNull(res, "Content-Encoding")).isEqualTo("br");
                assertThat(headerOrNull(res, "Content-Type")).isEqualTo(
                        "text/plain; charset=utf-8");
                // Test would be more readable and fun by decompressing like the gzip one, but since JDK doesn't
                // support brotli yet, just compare the compressed content to avoid adding a complex dependency.
                final byte[] content = content(res);
                assertThat(content).containsExactly(
                        Resources.toByteArray(Resources.getResource(baseResourceDir + "foo/foo.txt.br")));
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testDecompressPreCompressedFile(String baseUri) throws Exception {
        final AggregatedHttpResponse response = WebClient.of(baseUri)
                                                         .get("/compressed/decompress/baz.txt").aggregate()
                                                         .join();
        // The compressed file was automatically decompressed by the server.
        assertThat(response.contentUtf8()).isEqualTo("baz\n");
        assertThat(response.headers().contentType().is(MediaType.PLAIN_TEXT_UTF_8)).isTrue();
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testUseUncompressFileIfNeedsDecompressing(String baseUri) throws Exception {
        final AggregatedHttpResponse response = WebClient.of(baseUri)
                                                         .get("/uncompressed/decompress/qux.json").aggregate()
                                                         .join();
        // Make sure that the uncompressed file is used to serve.
        assertThat(response.contentUtf8())
                .isEqualTo("\"This is different from qux.json.br and qux.json.gz for testing purpose.\"\n");
        assertThat(response.headers().contentType().is(MediaType.JSON)).isTrue();
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testFileSystemGet(String baseUri) throws Exception {
        final Path barFile = tmpDir.resolve("bar.html");
        final String expectedContentA = "<html/>";
        final String expectedContentB = "<html><body/></html>";
        writeFile(barFile, expectedContentA);

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final String lastModified;
            final String etag;
            HttpUriRequest req = new HttpGet(baseUri + "/fs/bar.html");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentA);
                lastModified = header(res, HttpHeaders.LAST_MODIFIED);
                etag = header(res, HttpHeaders.ETAG);
            }

            assert304NotModified(hc, baseUri, "/fs/bar.html", etag, lastModified);

            // Test if the 'If-Modified-Since' header works as expected after the file is modified.
            req = new HttpGet(baseUri + "/fs/bar.html");
            final Instant now = Instant.now();
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE,
                          DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(now, UTC)));

            // HTTP-date has no sub-second precision; just add a few seconds to the time.
            writeFile(barFile, expectedContentB);
            Files.setLastModifiedTime(barFile, FileTime.fromMillis(now.toEpochMilli() + 5000));

            final String newLastModified;
            final String newETag;
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentB);
                newLastModified = header(res, HttpHeaders.LAST_MODIFIED);
                newETag = header(res, HttpHeaders.ETAG);

                // Ensure that both 'Last-Modified' and 'ETag' changed.
                assertThat(newLastModified).isNotEqualTo(lastModified);
                assertThat(newETag).isNotEqualTo(etag);
            }

            // Test if the 'If-None-Match' header works as expected after the file is modified.
            req = new HttpGet(baseUri + "/fs/bar.html");
            req.setHeader(HttpHeaders.IF_NONE_MATCH, etag);

            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentB);

                // Ensure that both 'Last-Modified' and 'ETag' changed.
                assertThat(header(res, HttpHeaders.LAST_MODIFIED)).isEqualTo(newLastModified);
                assertThat(header(res, HttpHeaders.ETAG)).isEqualTo(newETag);
            }

            // Test if the cache detects the file removal correctly.
            final boolean deleted = Files.deleteIfExists(barFile);
            assertThat(deleted).isTrue();

            req = new HttpGet(baseUri + "/fs/bar.html");
            req.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());
            req.setHeader(HttpHeaders.CONNECTION, "close");

            try (CloseableHttpResponse res = hc.execute(req)) {
                assert404NotFound(res);
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testFileSystemGet_modifiedFile(String baseUri) throws Exception {
        final Path barFile = tmpDir.resolve("modifiedFile.html");
        final String expectedContentA = "<html/>";
        final String expectedContentB = "<html><body/></html>";
        writeFile(barFile, expectedContentA);
        final long barFileLastModified = Files.getLastModifiedTime(barFile).toMillis();

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(baseUri + "/fs/modifiedFile.html");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentA);
            }

            // Modify the file cached by the service. Update last modification time explicitly
            // so that it differs from the old value.
            writeFile(barFile, expectedContentB);
            Files.setLastModifiedTime(barFile, FileTime.fromMillis(barFileLastModified + 5000));

            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentB);
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testFileSystemGet_newFile(String baseUri) throws Exception {
        final String barFileName = baseUri.substring(baseUri.lastIndexOf('/') + 1) + "_newFile.html";
        assertThat(barFileName).isIn("cached_newFile.html", "uncached_newFile.html");

        final Path barFile = tmpDir.resolve(barFileName);
        final String expectedContentA = "<html/>";

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(baseUri + "/fs/" + barFileName);
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert404NotFound(res);
            }
            writeFile(barFile, expectedContentA);
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/html", expectedContentA);
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BaseUriProvider.class)
    void testFileSystemGetUtf8(String baseUri) throws Exception {
        final Path barFile = tmpDir.resolve("¢.txt");
        final String expectedContentA = "¢";
        writeFile(barFile, expectedContentA);

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(baseUri + "/fs/%C2%A2.txt");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assert200Ok(res, "text/plain", expectedContentA);
            }
        }
    }

    private static void writeFile(Path path, String content) throws Exception {
        // Retry to work around the `AccessDeniedException` in Windows.
        for (int i = 9; i >= 0; i--) {
            try {
                Files.write(path, content.getBytes(StandardCharsets.UTF_8));
                return;
            } catch (Exception e) {
                if (i == 0) {
                    throw e;
                }
                logger.warn("Unexpected exception while writing to {}:", path, e);
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void assert200Ok(CloseableHttpResponse res,
                                    @Nullable String expectedContentType,
                                    String expectedContent) throws Exception {
        assert200Ok(res, expectedContentType, content -> assertThat(content).isEqualTo(expectedContent));
    }

    private static void assert200Ok(CloseableHttpResponse res,
                                    @Nullable String expectedContentType,
                                    Consumer<String> contentAssertions) throws Exception {

        assertStatusLine(res, "HTTP/1.1 200 OK");

        // Ensure that the 'Date' header exists and is well-formed.
        final String date = headerOrNull(res, HttpHeaders.DATE);
        assertThat(date).isNotNull();
        DateFormatter.parseHttpDate(date);

        // Ensure that the 'Last-Modified' header exists and is well-formed.
        final String lastModified = headerOrNull(res, HttpHeaders.LAST_MODIFIED);
        assertThat(lastModified).isNotNull();
        DateFormatter.parseHttpDate(lastModified);

        // Ensure that the 'ETag' header exists and is well-formed.
        final String entityTag = headerOrNull(res, HttpHeaders.ETAG);
        assertThat(entityTag).matches(ETAG_PATTERN);

        // Ensure the content type is correct.
        if (expectedContentType != null) {
            assertThat(headerOrNull(res, HttpHeaders.CONTENT_TYPE)).startsWith(expectedContentType);
        } else {
            assertThat(res.containsHeader(HttpHeaders.CONTENT_TYPE)).isFalse();
        }

        // Ensure the content satisfies the condition.
        contentAssertions.accept(EntityUtils.toString(res.getEntity()).trim());
    }

    private static void assert304NotModified(
            CloseableHttpClient hc, String baseUri, String path,
            String expectedETag, String expectedLastModified) throws IOException {

        final String uri = baseUri + path;

        // Test if the 'If-None-Match' header works as expected. (a single etag)
        final HttpUriRequest req1 = new HttpGet(uri);
        req1.setHeader(HttpHeaders.IF_NONE_MATCH, expectedETag);

        try (CloseableHttpResponse res = hc.execute(req1)) {
            assert304NotModified(res, expectedETag, expectedLastModified);
        }

        // Test if the 'If-None-Match' header works as expected. (multiple etags)
        final HttpUriRequest req2 = new HttpGet(uri);
        req2.setHeader(HttpHeaders.IF_NONE_MATCH, "\"an-etag-that-never-matches\", " + expectedETag);

        try (CloseableHttpResponse res = hc.execute(req2)) {
            assert304NotModified(res, expectedETag, expectedLastModified);
        }

        // Test if the 'If-None-Match' header works as expected. (an asterisk)
        final HttpUriRequest req3 = new HttpGet(uri);
        req3.setHeader(HttpHeaders.IF_NONE_MATCH, "*");

        try (CloseableHttpResponse res = hc.execute(req3)) {
            assert304NotModified(res, expectedETag, expectedLastModified);
        }

        // Test if the 'If-Modified-Since' header works as expected.
        final HttpUriRequest req4 = new HttpGet(uri);
        req4.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());

        try (CloseableHttpResponse res = hc.execute(req4)) {
            assert304NotModified(res, expectedETag, expectedLastModified);
        }

        // 'If-Modified-Since' should never be evaluated if 'If-None-Match' exists.
        final HttpUriRequest req5 = new HttpGet(uri);
        req5.setHeader(HttpHeaders.IF_NONE_MATCH, "\"an-etag-that-never-matches\"");
        req5.setHeader(HttpHeaders.IF_MODIFIED_SINCE, currentHttpDate());

        try (CloseableHttpResponse res = hc.execute(req5)) {
            // Should not receive '304 Not Modified' because the etag did not match.
            assertStatusLine(res, "HTTP/1.1 200 OK");

            // Read the content fully so that Apache HC does not close the connection prematurely.
            ByteStreams.exhaust(res.getEntity().getContent());
        }
    }

    private static void assert304NotModified(
            CloseableHttpResponse res, String expectedEtag, String expectedLastModified) {

        assertStatusLine(res, "HTTP/1.1 304 Not Modified");

        // Ensure that the 'ETag' header did not change.
        assertThat(headerOrNull(res, HttpHeaders.ETAG)).isEqualTo(expectedEtag);

        // Ensure that the 'Last-Modified' header did not change.
        assertThat(headerOrNull(res, HttpHeaders.LAST_MODIFIED)).isEqualTo(expectedLastModified);

        // Ensure that the 'Content-Length' header does not exist.
        assertThat(res.containsHeader(HttpHeaders.CONTENT_LENGTH)).isFalse();

        // Ensure that the content does not exist.
        assertThat(res.getEntity()).isNull();
    }

    private static void assert404NotFound(CloseableHttpResponse res) {
        assertStatusLine(res, "HTTP/1.1 404 Not Found");
        // Ensure that the 'Last-Modified' header does not exist.
        assertThat(res.getFirstHeader(HttpHeaders.LAST_MODIFIED)).isNull();
    }

    private static void assertStatusLine(CloseableHttpResponse res, String expectedStatusLine) {
        assertThat(res.getStatusLine().toString()).isEqualTo(expectedStatusLine);
    }

    private static String header(CloseableHttpResponse res, String name) {
        final String value = headerOrNull(res, name);
        assertThat(value).withFailMessage("The response must contain the header '%s'.", name).isNotNull();
        return value;
    }

    @Nullable
    private static String headerOrNull(CloseableHttpResponse res, String name) {
        if (!res.containsHeader(name)) {
            return null;
        }
        return res.getFirstHeader(name).getValue();
    }

    private static byte[] content(CloseableHttpResponse res) throws IOException {
        return ByteStreams.toByteArray(res.getEntity().getContent());
    }

    private static String contentString(CloseableHttpResponse res) throws IOException {
        return new String(ByteStreams.toByteArray(res.getEntity().getContent()), StandardCharsets.UTF_8);
    }

    private static String currentHttpDate() {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(UTC));
    }

    private static class BaseUriProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(server.httpUri() + "/cached",
                             server.httpUri() + "/uncached").map(Arguments::of);
        }
    }
}
