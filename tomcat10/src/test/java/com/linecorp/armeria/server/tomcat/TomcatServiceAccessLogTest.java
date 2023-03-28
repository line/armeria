/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.tomcat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.CharArrayWriter;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.AbstractAccessLogValve;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.tomcat.TomcatVersion;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class TomcatServiceAccessLogTest {
    private static Tomcat tomcat;

    private static final FakeAccessLogValve accessLogValve = FakeAccessLogValve.create();

    public static final class FakeAccessLogValve extends AbstractAccessLogValve {
        static FakeAccessLogValve create() {
            final FakeAccessLogValve fakeAccessLogValve = new FakeAccessLogValve();
            // '%D' returns milliseconds in Tomcat 9 but microseconds in Tomcat 10.
            // https://github.com/apache/tomcat/blob/e75b643aa8d94aa8e467be204747a5ec0de11c6a/java/org/apache/catalina/valves/AbstractAccessLogValve.java#L1372
            fakeAccessLogValve.setPattern("%D");
            return fakeAccessLogValve;
        }

        private final BlockingQueue<String> logs = new ArrayBlockingQueue<>(100);

        private FakeAccessLogValve() {
        }

        @Override
        protected void log(CharArrayWriter message) {
            logs.add(message.toString());
        }

        @Nullable
        String popLog() {
            return logs.poll();
        }

        void reset() {
            logs.clear();
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // Prepare Tomcat instances.
            tomcat = new Tomcat();
            tomcat.setPort(0);
            tomcat.setBaseDir("build" + File.separatorChar +
                              "tomcat-" + TomcatServiceAccessLogTest.class.getSimpleName());
            TomcatUtil.engine(tomcat.getService(), "bar");
            tomcat.getEngine().getPipeline().addValve(accessLogValve);

            // Start the Tomcats.
            tomcat.start();
            // Bind them to the Server.
            sb.serviceUnder("/no-webapp/", TomcatService.of(tomcat));
        }
    };

    @AfterAll
    static void destroyTomcat() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @BeforeEach
    void beforeEach() {
        accessLogValve.reset();
    }

    @Test
    void haveCorrectProcessingTime() throws Exception {
        final BlockingWebClient client = WebClient.of(server.httpUri()).blocking();
        client.get("/no-webapp/");

        long maybeMillis = Long.parseLong(accessLogValve.popLog());
        if (TomcatVersion.major() >= 10) {
            maybeMillis = TimeUnit.MICROSECONDS.toMillis(maybeMillis);
        }

        assertThat(maybeMillis).isLessThan(Duration.ofSeconds(10).toMillis());
    }
}
