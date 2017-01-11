package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.client.endpoint.AbstractServiceServer;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.hbase.IllegalArgument;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

public class RetryingRpcClientTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    HelloService.Iface serviceHandler;

    @Test
    public void execute() throws Exception {
        try (ServiceServer server = new ServiceServer(serviceHandler).start()) {
            HelloService.Iface client = new ClientBuilder(
                    "tbinary+http://127.0.0.1:" + server.port() + "/thrift")
                    .decorator(RpcRequest.class, RpcResponse.class,
                               RetryingRpcClient.newDecorator(3))
                    .build(HelloService.Iface.class);
            when(serviceHandler.hello(anyString()))
                    .thenThrow(new IllegalArgument())
                    .thenThrow(new IllegalArgument())
                    .thenReturn("world");
            assertThat(client.hello("hello")).isEqualTo("world");
            verify(serviceHandler, times(3)).hello("hello");
        }
    }

    private static class ServiceServer extends AbstractServiceServer {
        private final HelloService.Iface handler;

        ServiceServer(HelloService.Iface handler) {
            this.handler = handler;
        }

        @Override
        protected void configureServer(ServerBuilder sb) throws Exception {
            sb.serviceAt("/thrift", THttpService.of(handler));
        }
    }
}
