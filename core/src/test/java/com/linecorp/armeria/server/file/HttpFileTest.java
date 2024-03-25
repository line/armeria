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
package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.server.file.HttpFileBuilder.ClassPathHttpFileBuilder;

class HttpFileTest {

    @Test
    void additionalHeaders() {
        final HttpFile f = HttpFile.builder(ClassLoader.getSystemClassLoader(),
                                            "java/lang/Object.class")
                                   .addHeader("foo", "1")
                                   .addHeader("foo", "2")
                                   .setHeader("bar", "3")
                                   .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                   .cacheControl(ServerCacheControl.REVALIDATED)
                                   .build();

        // Make sure content-type auto-detection is disabled.
        assertThat(((AbstractHttpFile) f).contentType()).isNull();

        // Make sure all additional headers are set as expected.
        final HttpHeaders headers = f.readHeaders(CommonPools.blockingTaskExecutor()).join();
        assertThat(headers).isNotNull();
        assertThat(headers.getAll(HttpHeaderNames.of("foo"))).containsExactly("1", "2");
        assertThat(headers.getAll(HttpHeaderNames.of("bar"))).containsExactly("3");
        assertThat(headers.getAll(HttpHeaderNames.CONTENT_TYPE))
                .containsExactly(MediaType.PLAIN_TEXT_UTF_8.toString());
        assertThat(headers.getAll(HttpHeaderNames.CACHE_CONTROL))
                .containsExactly(ServerCacheControl.REVALIDATED.asHeaderValue());
    }

    @Test
    void leadingSlashInResourcePath() {
        final HttpFile f = HttpFile.of(ClassLoader.getSystemClassLoader(), "/java/lang/Object.class");
        final HttpFileAttributes attrs = f.readAttributes(CommonPools.blockingTaskExecutor()).join();
        assertThat(attrs).isNotNull();
        assertThat(attrs.length()).isPositive();
    }

    @Test
    void redirect() throws Exception {
        final HttpFile file = HttpFile.ofRedirect("/foo/bar?a=b");
        final HttpResponse response = file.asService().serve(null, HttpRequest.of(HttpMethod.GET, "/foo"));
        final AggregatedHttpResponse agg = response.aggregate().join();
        assertThat(agg.status()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(agg.headers().get(HttpHeaderNames.LOCATION)).isEqualTo("/foo/bar?a=b");
    }

    @Test
    void createFromFileUrl(@TempDir Path tempDir) throws Exception {
        final URL file = tempDir.resolve("test.txt").toUri().toURL();
        final HttpFileBuilder builder = HttpFile.builder(file);
        assertThat(builder).isInstanceOf(HttpFileBuilder.FileSystemHttpFileBuilder.class);
    }

    @Test
    void createFromHttpUrl() throws Exception {
        final URL url = new URL("https://line.me");
        final String exMsg = "Unsupported URL: https://line.me " +
                             "(must start with 'file:', 'jar:file', 'jar:nested', 'jrt:' or 'bundle:')";
        assertThatThrownBy(() -> HttpFile.builder(url)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(exMsg);
    }

    private static class NestedJarURLStreamHandler extends URLStreamHandler {
        @Override
        protected URLConnection openConnection(URL u) {
            throw new UnsupportedOperationException("mocked");
        }
    }

    @Test
    void createFromNestedUrl() throws Exception {
        // Define a Spring Boot 3 nested URL
        final String nestedURL = "jar:nested:/root/exec.jar/!BOOT-INF/lib/nested.jar!/foo.js";

        // Construct the URL directly because it isn't a built-in type
        final URL url = new URL(null, nestedURL, new NestedJarURLStreamHandler());

        // Ensure it is detected as a classpath resource
        assertThat(HttpFile.builder(url)).isInstanceOf(ClassPathHttpFileBuilder.class);
    }

    @Test
    // in JDK 8, getting class by getResource returns jar protocol
    @DisabledOnJre(JRE.JAVA_8)
    void createFromJrtUrl() {
        final URL jarFileUrl = ClassLoader.getSystemClassLoader().getResource("java/lang/Object.class");
        assertThat(jarFileUrl.getProtocol()).isEqualTo("jrt");
        final HttpFileBuilder builder = HttpFile.builder(jarFileUrl);
        assertThat(builder).isInstanceOf(HttpFileBuilder.ClassPathHttpFileBuilder.class);
    }

    @Test
    void createFromJarFileUrl() {
        final URL jarFileUrl = Test.class.getClassLoader().getResource("META-INF/LICENSE.md");
        assertThat(jarFileUrl.getProtocol()).isEqualTo("jar");
        final HttpFileBuilder builder = HttpFile.builder(jarFileUrl);
        assertThat(builder).isInstanceOf(HttpFileBuilder.ClassPathHttpFileBuilder.class);
    }

    @Test
    void createFromJarHttpUrl() throws Exception {
        final URL jarHttpUrl = new URL("jar:http://www.foo.com/bar/baz.jar!/COM/foo/Quux.class");
        final String exMsg = "Unsupported URL: jar:http://www.foo.com/bar/baz.jar!/COM/foo/Quux.class " +
                             "(must start with 'file:', 'jar:file', 'jar:nested', 'jrt:' or 'bundle:')";
        assertThatThrownBy(() -> HttpFile.builder(jarHttpUrl)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(exMsg);
    }
}
