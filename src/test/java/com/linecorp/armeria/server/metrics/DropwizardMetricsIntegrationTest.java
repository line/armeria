package com.linecorp.armeria.server.metrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.codahale.metrics.MetricRegistry;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.metrics.MetricCollectingClient;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.ThriftService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.Iface;

public class DropwizardMetricsIntegrationTest extends AbstractServerTest {

    private MetricRegistry metricRegistry = new MetricRegistry();

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        sb.serviceAt("/helloservice", ThriftService.of((Iface) name -> {
            if (name.equals("world")) {
                return "success";
            }
            throw new IllegalArgumentException("bad argument");
        }).decorate(MetricCollectingService.newDropwizardDecorator("HelloService", metricRegistry)));
    }

    @Test(timeout = 10000L)
    public void normal() throws Exception {
        makeRequest("world");
        makeRequest("world");
        makeRequest("space");
        makeRequest("world");
        makeRequest("space");
        makeRequest("space");
        makeRequest("world");

        assertEquals(3, metricRegistry.getMeters()
                                      .get("server.HelloService.hello.failures")
                                      .getCount());
        assertEquals(3, metricRegistry.getMeters()
                                      .get("client.HelloService.hello.failures")
                                      .getCount());
        assertEquals(4, metricRegistry.getMeters()
                                      .get("server.HelloService.hello.successes")
                                      .getCount());
        assertEquals(4, metricRegistry.getMeters()
                                      .get("client.HelloService.hello.successes")
                                      .getCount());
        assertEquals(7, metricRegistry.getTimers()
                                      .get("server.HelloService.hello.requests")
                                      .getCount());
        assertEquals(7, metricRegistry.getTimers()
                                      .get("client.HelloService.hello.requests")
                                      .getCount());
        assertEquals(210, metricRegistry.getMeters()
                                      .get("server.HelloService.hello.requestBytes")
                                      .getCount());
        assertEquals(210, metricRegistry.getMeters()
                                      .get("client.HelloService.hello.requestBytes")
                                      .getCount());
        assertEquals(368, metricRegistry.getMeters()
                                      .get("server.HelloService.hello.responseBytes")
                                      .getCount());
        assertEquals(368, metricRegistry.getMeters()
                                      .get("client.HelloService.hello.responseBytes")
                                      .getCount());
    }

    private void makeRequest(String name) {
        HelloService.Iface client = new ClientBuilder("tbinary+" + uri("/helloservice"))
                .decorator(MetricCollectingClient.newDropwizardDecorator("HelloService", metricRegistry))
                .build(HelloService.Iface.class);
        try {
            client.hello(name);
        } catch (Throwable t) {
            // Ignore, we will count these up
        }
    }
}
