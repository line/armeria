/*
 * Copyright 2018 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;

public class ServerBuilderTest {

    @Test
    public void acceptDuplicatePort() throws Exception {
        final Server server = new ServerBuilder()
                .http(8080)
                .https(8080)
                .tlsSelfSigned()
                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                .build();

        final List<ServerPort> ports = server.config().ports();
        assertThat(ports.size()).isOne(); // merged
        assertThat(ports.get(0).protocols())
                .contains(SessionProtocol.HTTP, SessionProtocol.HTTPS);
    }

    @Test
    public void treatAsSeparatePortIfZeroIsSpecifiedManyTimes() throws Exception {
        final Server server = new ServerBuilder()
                .http(0)
                .http(0)
                .https(0)
                .tlsSelfSigned()
                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                .build();

        final List<ServerPort> ports = server.config().ports();
        assertThat(ports.size()).isEqualTo(3);
        assertThat(ports.get(0).protocols()).containsOnly(SessionProtocol.HTTP);
        assertThat(ports.get(1).protocols()).containsOnly(SessionProtocol.HTTP);
        assertThat(ports.get(2).protocols()).containsOnly(SessionProtocol.HTTPS);
    }

    @Test
    public void numMaxConnections() {
        final ServerBuilder sb = new ServerBuilder();
        assertThat(sb.maxNumConnections()).isEqualTo(Integer.MAX_VALUE);
    }

    /**
     * Makes sure each virtual host can have its custom logger name.
     */
    @Test
    public void setAccessLoggerTest1() {
        final Server sb = new ServerBuilder()
                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                .accessLogger(LoggerFactory.getLogger("default"))
                .withVirtualHost("*.example.com")
                .and()
                .withVirtualHost("*.example2.com")
                .accessLogger("com.ex2")
                .and()
                .withVirtualHost("*.example3.com")
                .accessLogger(host -> LoggerFactory.getLogger("com.ex3"))
                .and()
                .virtualHost(new VirtualHostBuilder("def.example4.com", "*.example4.com").build())
                .virtualHost(new VirtualHostBuilder("def.example5.com", "*.example5.com")
                                     .accessLogger("com.ex5").build())
                .build();
        assertThat(sb.config().defaultVirtualHost()).isNotNull();
        assertThat(sb.config().defaultVirtualHost().accessLogger().getName()).isEqualTo("default");

        assertThat(sb.config().findVirtualHost("*.example.com").accessLogger().getName())
                .isEqualTo("default");

        assertThat(sb.config().findVirtualHost("*.example2.com").accessLogger().getName())
                .isEqualTo("com.ex2");

        assertThat(sb.config().findVirtualHost("*.example3.com").accessLogger().getName())
                .isEqualTo("com.ex3");

        assertThat(sb.config().findVirtualHost("*.example4.com").accessLogger().getName())
                .isEqualTo("default");

        assertThat(sb.config().findVirtualHost("*.example5.com").accessLogger().getName())
                .isEqualTo("com.ex5");
    }

    /**
     * Makes sure that {@link VirtualHost}s can have a proper {@link Logger} used for writing access
     * when a user specifies the default access logger via {@link ServerBuilder#accessLogger(String)}.
     */
    @Test
    public void setAccessLoggerTest2() {
        final Server sb = new ServerBuilder()
                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                .accessLogger("test.default")
                .withVirtualHost("*.example.com")
                .and()
                .build();
        assertThat(sb.config().defaultVirtualHost().accessLogger().getName())
                .isEqualTo("test.default");
        assertThat(sb.config().findVirtualHost("*.example.com").accessLogger().getName())
                .isEqualTo("test.default");
    }

    /**
     * Makes sure that {@link VirtualHost}s can have a proper {@link Logger} used for writing access
     * when a user doesn't specify it.
     */
    @Test
    public void defaultAccessLoggerTest() {
        final Server sb = new ServerBuilder()
                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                .withVirtualHost("*.example.com")
                .and()
                .withVirtualHost("*.example2.com")
                .and()
                .build();
        assertThat(sb.config().findVirtualHost("*.example.com").accessLogger().getName())
                .isEqualTo("com.linecorp.armeria.logging.access.com.example");
        assertThat(sb.config().findVirtualHost("*.example2.com").accessLogger().getName())
                .isEqualTo("com.linecorp.armeria.logging.access.com.example2");
    }

    /**
     * Makes sure that {@link ServerBuilder#build()} throws {@link IllegalStateException}
     * when the access logger of a {@link VirtualHost} set by a user is {@code null}.
     */
    @Test
    public void buildIllegalExceptionTest() {
        final ServerBuilder sb = new ServerBuilder()
                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                .accessLogger(host -> null);
        assertThatThrownBy(sb::build).isInstanceOf(IllegalStateException.class);
        final ServerBuilder sb2 = new ServerBuilder()
                .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                .accessLogger(host -> {
                    if ("*.example.com".equals(host.hostnamePattern())) {
                        return null;
                    }
                    return LoggerFactory.getLogger("default");
                })
                .withVirtualHost("*.example.com").and();
        assertThatThrownBy(sb2::build).isInstanceOf(IllegalStateException.class);
    }
}
