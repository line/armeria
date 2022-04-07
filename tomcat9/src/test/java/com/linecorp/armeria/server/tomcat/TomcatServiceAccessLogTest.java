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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.AbstractAccessLogValve;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class TomcatServiceAccessLogTest {
    private static Tomcat tomcat;

    private static final FakeAccessLogValve accessLogValve = FakeAccessLogValve.create();

    public static final class FakeAccessLogValve extends AbstractAccessLogValve {
        static FakeAccessLogValve create() {
            final FakeAccessLogValve fakeAccessLogValve = new FakeAccessLogValve();
            fakeAccessLogValve.setPattern("%D");
            return fakeAccessLogValve;
        }

        private List<String> logs = Collections.synchronizedList(new ArrayList<>());

        private FakeAccessLogValve() {
        }

        @Override
        protected void log(CharArrayWriter message) {
            logs.add(message.toString());
        }

        String popLog() {
            return logs.remove(0);
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
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse ignored = hc.execute(new HttpGet(server.httpUri() + "/no-webapp/"))) {
                final String log = accessLogValve.popLog();
                assertThat(Long.parseLong(log)).isLessThan(Duration.ofSeconds(10).toMillis());
            }
        }
    }
}
