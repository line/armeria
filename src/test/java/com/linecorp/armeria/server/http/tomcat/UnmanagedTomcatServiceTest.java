/*
 * Copyright 2015 LINE Corporation
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

import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;

public class UnmanagedTomcatServiceTest extends AbstractServerTest {
    private static Tomcat tomcat;

    @BeforeClass
    public static void createTomcat() {
        tomcat = new Tomcat();
    }

    @Override
    protected void configureServer(ServerBuilder sb) throws LifecycleException {
        tomcat.start();
        Connector configuredConnector = tomcat.getConnector();

        sb.serviceUnder("/empty/", TomcatService.forConnector("somehost", new Connector()))
          .serviceUnder("/some/", TomcatService.forConnector("somehost", configuredConnector));
    }

    @Test
    public void testUnavailableStatus() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/empty/")))) {
                // as connector is not configured, TomcatServiceInvocationHandler will throw.
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 503 Service Unavailable"));
            }
        }
    }

    @Test
    public void testSomeStatus() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(uri("/some/")))) {
                // as no webapp is configured inside tomcat, 500 will be thrown.
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 500 Internal Server Error"));
            }
        }
    }

    @After
    public void stopTomcat() throws LifecycleException {
        tomcat.stop();
    }

    @AfterClass
    public static void destroyTomcat() throws LifecycleException {
        tomcat.destroy();
    }
}
