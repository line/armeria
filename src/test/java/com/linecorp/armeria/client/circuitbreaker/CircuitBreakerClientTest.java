/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.function.Function;

import org.junit.Test;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.RemoteInvoker;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

public class CircuitBreakerClientTest {

    // Remote invocation parameters

    private static EventLoop eventLoop = new DefaultEventLoop();

    private static ClientOptions options = ClientOptions.of();

    private static Object[] args = { "a", "b" };

    private static URI uri = URI.create("http://dummyhost:8080");

    private static class EmptyService {
        public void methodA() {}
    }

    private static Method methodA() throws NoSuchMethodException {
        return EmptyService.class.getMethod("methodA");
    }

    @Test
    public void testSingletonDecorator() throws Exception {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);

        Function<Client, Client> decorator = CircuitBreakerClient.newDecorator(circuitBreaker);
        invoke(decorator);

        verify(circuitBreaker, times(1)).canRequest();
    }

    @Test
    public void testPerMethodDecorator() throws Exception {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        @SuppressWarnings("unchecked")
        Function<String, CircuitBreaker> factory = (Function<String, CircuitBreaker>) mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        Function<Client, Client> decorator = CircuitBreakerClient.newPerMethodDecorator(factory);
        invoke(decorator);
        invoke(decorator);

        verify(circuitBreaker, times(2)).canRequest();
        verify(factory, times(1)).apply("methodA");
    }

    @Test
    public void testPerHostDecorator() throws Exception {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        @SuppressWarnings("unchecked")
        Function<String, CircuitBreaker> factory = (Function<String, CircuitBreaker>) mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        Function<Client, Client> decorator = CircuitBreakerClient.newPerHostDecorator(factory);
        invoke(decorator);
        invoke(decorator);

        verify(circuitBreaker, times(2)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080");
    }

    @Test
    public void testPerHostAndMethodDecorator() throws Exception {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        @SuppressWarnings("unchecked")
        Function<String, CircuitBreaker> factory = (Function<String, CircuitBreaker>) mock(Function.class);
        when(factory.apply(any())).thenReturn(circuitBreaker);

        Function<Client, Client> decorator = CircuitBreakerClient.newPerHostAndMethodDecorator(factory);
        invoke(decorator);
        invoke(decorator);

        verify(circuitBreaker, times(2)).canRequest();
        verify(factory, times(1)).apply("dummyhost:8080#methodA");
    }

    public void invoke(Function<Client, Client> decorator) throws Exception {
        Client client = mock(Client.class);
        ClientCodec codec = mock(ClientCodec.class);
        RemoteInvoker invoker = mock(RemoteInvoker.class);

        when(client.codec()).thenReturn(codec);
        when(client.invoker()).thenReturn(invoker);

        Client decorated = decorator.apply(client);

        decorated.invoker().invoke(eventLoop, uri, options, codec, methodA(), args);
    }
}
