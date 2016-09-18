package com.linecorp.armeria.server.http;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Created by wangjunfei on 9/9/16.
 */
public class MyHttpServerTest {
    public void testServer() {
        ServerBuilder sb = new ServerBuilder();
        sb.port(0, SessionProtocol.HTTPS);
        sb.serviceUnder("/test", new AbstractHttpService() {
            @Override
            protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
                CompletableFuture<String> completableFuture = new CompletableFuture<String>();
            }
        });

    }
}
