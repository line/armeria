/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.netty.channel.unix.DomainSocketAddress;

class TextFormatterTest {
    @Test
    void size() throws Exception {
        assertThat(TextFormatter.size(100).toString()).isEqualTo("100B");
        assertThat(TextFormatter.size(100 * 1024 + 1).toString()).isEqualTo("100KiB(102401B)");
        assertThat(TextFormatter.size(100 * 1024 * 1024 + 1).toString()).isEqualTo("100MiB(104857601B)");
    }

    @Test
    void elapsed() throws Exception {
        assertThat(TextFormatter.elapsed(1, 100).toString()).isEqualTo("99ns");
        assertThat(TextFormatter.elapsed(TimeUnit.MICROSECONDS.toNanos(100) + 1,
                                         TimeUnit.NANOSECONDS).toString())
                .isEqualTo("100\u00B5s(100001ns)"); // microsecs
        assertThat(TextFormatter.elapsed(TimeUnit.MILLISECONDS.toNanos(100) + 1,
                                         TimeUnit.NANOSECONDS).toString())
                .isEqualTo("100ms(100000001ns)");
        assertThat(TextFormatter.elapsed(TimeUnit.SECONDS.toNanos(100) + 1,
                                         TimeUnit.NANOSECONDS).toString())
                .isEqualTo("100s(100000000001ns)");
    }

    @Test
    void elapsedAndSize() throws Exception {
        assertThat(TextFormatter.elapsedAndSize(1, 100, 1024 * 100).toString())
                .isEqualTo("99ns, 100KiB(102400B)");
    }

    @Test
    void epoch() throws Exception {
        assertThat(TextFormatter.epochMillis(1478601399123L).toString())
                .isEqualTo("2016-11-08T10:36:39.123Z(1478601399123)");
        assertThat(TextFormatter.epochMicros(1478601399123235L).toString())
                .isEqualTo("2016-11-08T10:36:39.123Z(1478601399123235)");
    }

    @Test
    void socketAddress() throws Exception {
        assertThat(TextFormatter.socketAddress(null)
                                .toString()).isEqualTo("null");
        assertThat(TextFormatter.socketAddress(new DomainSocketAddress("/foo"))
                                .toString()).isEqualTo("/foo");
        assertThat(TextFormatter.socketAddress(new InetSocketAddress("127.0.0.1", 80))
                                .toString()).isEqualTo("127.0.0.1:80");
        assertThat(TextFormatter.socketAddress(new InetSocketAddress("repo.maven.apache.org", 80))
                                .toString()).matches("^repo\\.maven\\.apache\\.org/.+:80$");
        assertThat(TextFormatter.socketAddress(InetSocketAddress.createUnresolved("foo.com", 443))
                                .toString()).isEqualTo("foo.com:443");
        assertThat(TextFormatter.socketAddress(new InetSocketAddress(InetAddress.getByAddress(
                "bar.com", new byte[] { 1, 2, 3, 4 }), 8080)).toString()).isEqualTo("bar.com/1.2.3.4:8080");
    }

    @Test
    void inetAddress() throws Exception {
        assertThat(TextFormatter.inetAddress(null)
                                .toString()).isEqualTo("null");
        assertThat(TextFormatter.inetAddress(InetAddress.getByAddress(new byte[] { 1, 2, 3, 4 }))
                                .toString()).isEqualTo("1.2.3.4");
        assertThat(TextFormatter.inetAddress(InetAddress.getByAddress("foo", new byte[] { 5, 6, 7, 8 }))
                                .toString()).isEqualTo("foo/5.6.7.8");
        assertThat(TextFormatter.inetAddress(InetAddress.getByAddress("1.2.3.4", new byte[] { 5, 6, 7, 8 }))
                                .toString()).isEqualTo("1.2.3.4/5.6.7.8");
        assertThat(TextFormatter.inetAddress(InetAddress.getByName("repo.maven.apache.org"))
                                .toString()).matches("^repo\\.maven\\.apache\\.org/.+$");
    }
}
