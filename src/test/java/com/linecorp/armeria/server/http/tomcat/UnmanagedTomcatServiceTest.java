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
package com.linecorp.armeria.server.http.tomcat;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;

public class UnmanagedTomcatServiceTest extends AbstractServerTest {
    private static Tomcat tomcatWithWebApp;
    private static Tomcat tomcatWithoutWebApp;

    @BeforeClass
    public static void createTomcat() throws Exception {
        tomcatWithWebApp = new Tomcat();
        tomcatWithWebApp.setPort(0);
        tomcatWithWebApp.setBaseDir("target" + File.separatorChar +
                                    "tomcat-" + UnmanagedTomcatServiceTest.class.getSimpleName() + "-1");
        tomcatWithWebApp.addWebapp(
                "",
                new File("target" + File.separatorChar +
                         "test-classes" + File.separatorChar + "tomcat_service").getAbsolutePath());
        tomcatWithWebApp.getService().getContainer().setName("tomcatWithWebApp");

        tomcatWithoutWebApp = new Tomcat();
        tomcatWithoutWebApp.setPort(0);
        tomcatWithoutWebApp.setBaseDir("target" + File.separatorChar +
                                       "tomcat-" + UnmanagedTomcatServiceTest.class.getSimpleName() + "-2");
    }

    @Override
    protected void configureServer(ServerBuilder sb) throws LifecycleException {
        tomcatWithWebApp.start();
        tomcatWithoutWebApp.start();

        sb.serviceUnder("/empty/", TomcatService.forConnector("someHost", new Connector()))
          .serviceUnder("/no-webapp/", TomcatService.forConnector(tomcatWithoutWebApp.getConnector()))
          .serviceUnder("/some-webapp/", TomcatService.forConnector(tomcatWithWebApp.getConnector()));
    }

    @Test
    public void testServiceUnavailable() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/empty/")))) {
                // as connector is not configured, TomcatServiceInvocationHandler will throw.
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 503 Service Unavailable"));
            }
        }
    }

    @Test
    public void testUnconfiguredWebApp() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/no-webapp/")))) {
                // as no webapp is configured inside tomcat, 404 will be thrown.
                System.err.println("Entity: " + EntityUtils.toString(res.getEntity()));
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 404 Not Found"));
            }
        }
    }

    @Test
    public void testOk() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/some-webapp/")))) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
            }
        }
    }

    @AfterClass
    public static void destroyTomcat() throws Exception {
        tomcatWithWebApp.stop();
        tomcatWithWebApp.destroy();
        tomcatWithoutWebApp.stop();
        tomcatWithoutWebApp.destroy();
    }
}
