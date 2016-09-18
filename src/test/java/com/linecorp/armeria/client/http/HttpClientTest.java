package com.linecorp.armeria.client.http;

import org.junit.Test;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;

/**
 * Created by wangjunfei on 9/9/16.
 */
public class HttpClientTest {

    public void testCommon() {
        HttpClient httpClient = Clients.newClient(ClientFactory.DEFAULT, "none+http://www.baidu.com",
                                                  HttpClient.class);
        httpClient.get("/").aggregate().thenApply(a -> {
            System.out.println(a.content().toStringUtf8());
            return null;
        });

    }

    @Test
    public void testMain() {
        testCommon();
        try {
            Thread.currentThread().sleep(1000 * 10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
