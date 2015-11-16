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
package com.linecorp.armeria.client.thrift;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.net.URI;

import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.Test;

import com.linecorp.armeria.client.ClientCodec.EncodeResult;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

public class ThriftClientCodecTest {

    private final URI uri;
    private final Scheme scheme;

    private final ThriftClientCodec syncClient;
    private final ThriftClientCodec asyncClient;
    private final Channel channel;

    private final Method helloMethod;
    private final Method asyncHelloMethod;

    private static final AsyncMethodCallback<?> DUMMY_CALLBACK = new AsyncMethodCallback<String>() {
        @Override
        public void onComplete(String response) {
        }

        @Override
        public void onError(Exception exception) {
        }
    };

    @SuppressWarnings("unchecked")
    private static <T> AsyncMethodCallback<T> dummyCallback() {
        return (AsyncMethodCallback<T>) DUMMY_CALLBACK;
    }

    public ThriftClientCodecTest() throws Exception {
        uri = new URI("tbinary+http://localhost/hello");
        scheme = Scheme.parse(uri.getScheme());
        syncClient = new ThriftClientCodec(uri, scheme, HelloService.Iface.class,
                                           ThriftProtocolFactories.BINARY);
        asyncClient = new ThriftClientCodec(uri, scheme, HelloService.AsyncIface.class,
                                            ThriftProtocolFactories.BINARY);
        channel = new EmbeddedChannel();
        helloMethod = HelloService.Iface.class.getMethod("hello", String.class);
        asyncHelloMethod = HelloService.AsyncIface.class.getMethod("hello",
                                                                   String.class, AsyncMethodCallback.class);
    }

    @Test
    public void testEncodeRequest() throws NoSuchMethodException {
        Object[] args = { "world" };
        EncodeResult result = syncClient.encodeRequest(channel, helloMethod, args);
        verifySuccessResult(result);
    }

    @Test
    public void testEncodeRequestAsync() throws NoSuchMethodException {
        Object[] args = { "world", dummyCallback() };
        EncodeResult result = asyncClient.encodeRequest(channel, helloMethod, args);
        verifySuccessResult(result);
    }

    private void verifySuccessResult(EncodeResult result) {
        assertThat(result.isSuccess(), is(true));
        ServiceInvocationContext ctx = result.invocationContext();
        assertThat(ctx.scheme(), is(scheme));
        assertThat(ctx.originalRequest(), is(not(nullValue())));
        assertThat(ctx.method(), is("hello"));
        assertThat(ctx.path(), is(uri.getPath()));
        assertThat(ctx.mappedPath(), is(uri.getPath()));
        assertThat(ctx.invocationId(), is("1"));
    }

    @Test(expected = IllegalStateException.class)
    public void testEncodeRequestFailed() throws NoSuchMethodException {
        Object[] args = { "world" };
        EncodeResult result = asyncClient.encodeRequest(channel, asyncHelloMethod, args);
        assertThat(result.isSuccess(), is(false));
        assertThat(result.cause(), is(notNullValue()));
        result.invocationContext();
        fail("should exception ocuured when getting invocation Context");
    }
}
