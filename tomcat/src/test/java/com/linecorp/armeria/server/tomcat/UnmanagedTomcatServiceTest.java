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

package com.linecorp.armeria.server.tomcat;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.internal.webapp.WebAppContainerTest;
import com.linecorp.armeria.testing.server.ServerRule;

public class UnmanagedTomcatServiceTest {

    private static Tomcat tomcatWithWebApp;
    private static Tomcat tomcatWithoutWebApp;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // Prepare Tomcat instances.
            tomcatWithWebApp = new Tomcat();
            tomcatWithWebApp.setPort(0);
            tomcatWithWebApp.setBaseDir("build" + File.separatorChar +
                                        "tomcat-" + UnmanagedTomcatServiceTest.class.getSimpleName() + "-1");

            tomcatWithWebApp.addWebapp("", WebAppContainerTest.webAppRoot().getAbsolutePath());
            TomcatUtil.engine(tomcatWithWebApp.getService()).setName("tomcatWithWebApp");

            tomcatWithoutWebApp = new Tomcat();
            tomcatWithoutWebApp.setPort(0);
            tomcatWithoutWebApp.setBaseDir("build" + File.separatorChar +
                                           "tomcat-" + UnmanagedTomcatServiceTest.class.getSimpleName() + "-2");

            // Start the Tomcats.
            tomcatWithWebApp.start();
            tomcatWithoutWebApp.start();

            // Bind them to the Server.
            sb.serviceUnder("/empty/", TomcatService.forConnector("someHost", new Connector()))
              .serviceUnder("/no-webapp/", TomcatService.forConnector(tomcatWithoutWebApp.getConnector()))
              .serviceUnder("/some-webapp/", TomcatService.forConnector(tomcatWithWebApp.getConnector()));
        }
    };

    @AfterClass
    public static void destroyTomcat() throws Exception {
        if (tomcatWithWebApp != null) {
            tomcatWithWebApp.stop();
            tomcatWithWebApp.destroy();
        }
        if (tomcatWithoutWebApp != null) {
            tomcatWithoutWebApp.stop();
            tomcatWithoutWebApp.destroy();
        }
    }

    @Test
    public void serviceUnavailable() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.uri("/empty/")))) {
                // as connector is not configured, TomcatServiceInvocationHandler will throw.
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 503 Service Unavailable"));
            }
        }
    }

    @Test
    public void unconfiguredWebApp() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.uri("/no-webapp/")))) {
                // as no webapp is configured inside tomcat, 404 will be thrown.
                System.err.println("Entity: " + EntityUtils.toString(res.getEntity()));
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
        }
    }

    @Test
    public void ok() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.uri("/some-webapp/")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
        }
    }
}
