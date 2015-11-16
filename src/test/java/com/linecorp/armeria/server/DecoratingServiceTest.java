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

package com.linecorp.armeria.server;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.concurrent.Executor;

import org.junit.Test;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Promise;

public class DecoratingServiceTest {

    @Test
    public void testUndecorated() {
        Service service = new A();

        // Test unwrapping a service
        assertThat(service.as(A.class).isPresent(), is(true));
        assertThat(service.as(A.class).get(), is(instanceOf(A.class)));
        assertThat(service.as(B.class).isPresent(), is(false));
        assertThat(service.as(C.class).isPresent(), is(false));
        assertThat(service.as(D.class).isPresent(), is(false));

        // Test unwrapping a codec
        assertThat(service.codec().as(AC.class).isPresent(), is(true));
        assertThat(service.codec().as(AC.class).get(), is(instanceOf(AC.class)));
        assertThat(service.codec().as(BC.class).isPresent(), is(false));
        assertThat(service.codec().as(CC.class).isPresent(), is(false));
        assertThat(service.codec().as(DC.class).isPresent(), is(false));

        // Test unwrapping a handler
        assertThat(service.handler().as(AH.class).isPresent(), is(true));
        assertThat(service.handler().as(AH.class).get(), is(instanceOf(AH.class)));
        assertThat(service.handler().as(BH.class).isPresent(), is(false));
        assertThat(service.handler().as(CH.class).isPresent(), is(false));
        assertThat(service.handler().as(DH.class).isPresent(), is(false));
    }

    @Test
    public void testDecorated() {
        Service service = new A().decorate(B::new).decorate(C::new);

        // Test unwrapping a service
        assertThat(service.as(A.class).isPresent(), is(true));
        assertThat(service.as(A.class).get(), is(instanceOf(A.class)));
        assertThat(service.as(B.class).isPresent(), is(true));
        assertThat(service.as(B.class).get(), is(instanceOf(B.class)));
        assertThat(service.as(C.class).isPresent(), is(true));
        assertThat(service.as(C.class).get(), is(instanceOf(C.class)));
        assertThat(service.as(D.class).isPresent(), is(false));

        // Test unwrapping a codec
        assertThat(service.codec().as(AC.class).isPresent(), is(true));
        assertThat(service.codec().as(AC.class).get(), is(instanceOf(AC.class)));
        assertThat(service.codec().as(BC.class).isPresent(), is(true));
        assertThat(service.codec().as(BC.class).get(), is(instanceOf(BC.class)));
        assertThat(service.codec().as(CC.class).isPresent(), is(true));
        assertThat(service.codec().as(CC.class).get(), is(instanceOf(CC.class)));
        assertThat(service.codec().as(DC.class).isPresent(), is(false));

        // Test unwrapping a handler
        assertThat(service.handler().as(AH.class).isPresent(), is(true));
        assertThat(service.handler().as(AH.class).get(), is(instanceOf(AH.class)));
        assertThat(service.handler().as(BH.class).isPresent(), is(true));
        assertThat(service.handler().as(BH.class).get(), is(instanceOf(BH.class)));
        assertThat(service.handler().as(CH.class).isPresent(), is(true));
        assertThat(service.handler().as(CH.class).get(), is(instanceOf(CH.class)));
        assertThat(service.handler().as(DH.class).isPresent(), is(false));
    }

    static final class A extends SimpleService {
        A() {
            super(new AC(), new AH());
        }
    }

    static final class B extends DecoratingService {
        B(Service service) {
            super(service, BC::new, BH::new);
        }
    }

    static final class C extends DecoratingService {
        C(Service service) {
            super(service, CC::new, CH::new);
        }
    }

    static final class D extends DecoratingService {
        D(Service service) {
            super(service, DC::new, DH::new);
        }
    }

    static final class AC implements ServiceCodec {
        @Override
        public DecodeResult decodeRequest(
                Channel ch, SessionProtocol sessionProtocol, String hostname, String path, String mappedPath,
                ByteBuf in, Object originalRequest, Promise<Object> promise) throws Exception {
            return null;
        }

        @Override
        public boolean failureResponseFailsSession(ServiceInvocationContext ctx) {
            return false;
        }

        @Override
        public ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception {
            return null;
        }

        @Override
        public ByteBuf encodeFailureResponse(ServiceInvocationContext ctx, Throwable cause) throws Exception {
            return null;
        }
    }

    static final class BC extends DecoratingServiceCodec {
        BC(ServiceCodec codec) { super(codec); }
    }

    static final class CC extends DecoratingServiceCodec {
        CC(ServiceCodec codec) { super(codec); }
    }

    static final class DC extends DecoratingServiceCodec {
        DC(ServiceCodec codec) { super(codec); }
    }

    static final class AH implements ServiceInvocationHandler {
        @Override
        public void invoke(ServiceInvocationContext ctx, Executor blockingTaskExecutor, Promise<Object> promise)
                throws Exception {}
    }

    static final class BH extends DecoratingServiceInvocationHandler {
        BH(ServiceInvocationHandler handler) { super(handler); }
    }

    static final class CH extends DecoratingServiceInvocationHandler {
        CH(ServiceInvocationHandler handler) { super(handler); }
    }

    static final class DH extends DecoratingServiceInvocationHandler {
        DH(ServiceInvocationHandler handler) { super(handler); }
    }
}
