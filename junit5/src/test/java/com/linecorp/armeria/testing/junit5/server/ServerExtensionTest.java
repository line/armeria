package com.linecorp.armeria.testing.junit5.server;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.fail;

class ServerExtensionTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void requestContextCaptor() {
        WebClient client = WebClient.of(server.httpUri());
        client.get("/hello").aggregate().join();

        ServiceRequestContextCaptor captor = server.requestContextCaptor();
        assertThat(captor.size()).isEqualTo(1);

        try {
            assertThat(captor.take().request().uri().getPath()).isEqualTo("/hello");
        } catch (InterruptedException e) {
            fail("Should not fail");
        }
    }
}
