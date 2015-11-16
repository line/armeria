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

package com.linecorp.armeria.server.composition;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.MappedService;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceCallbackInvoker;
import com.linecorp.armeria.server.ServiceCodec;
import com.linecorp.armeria.server.ServiceInvocationHandler;
import com.linecorp.armeria.server.ServiceMapping;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;

/**
 * A skeletal {@link Service} implementation that enables composing multiple {@link Service}s into one.
 * Extend this class to build your own composite {@link Service}. e.g.
 * <pre>{@code
 * public class MyService extends AbstractCompositeService {
 *     public MyService() {
 *         super(CompositeServiceEntry.ofPrefix("/foo/"), new FooService()),
 *               CompositeServiceEntry.ofPrefix("/bar/"), new BarService()),
 *               CompositeServiceEntry.ofCatchAll(), new OtherService()));
 *     }
 * }
 * }</pre>
 *
 * @see AbstractCompositeServiceBuilder
 * @see CompositeServiceEntry
 */
public abstract class AbstractCompositeService implements Service {

    private static final AttributeKey<MappedService> MAPPED_SERVICE =
            AttributeKey.valueOf(AbstractCompositeService.class, "MAPPED_SERVICE");

    private final List<CompositeServiceEntry> services;
    private final ServiceMapping serviceMapping = new ServiceMapping();
    private final ServiceCodec codec = new CompositeServiceCodec();
    private final ServiceInvocationHandler handler = new CompositeServiceInvocationHandler();

    /**
     * Creates a new instance with the specified {@link CompositeServiceEntry}s.
     */
    protected AbstractCompositeService(CompositeServiceEntry... services) {
        this(Arrays.asList(requireNonNull(services, "services")));
    }

    /**
     * Creates a new instance with the specified {@link CompositeServiceEntry}s.
     */
    protected AbstractCompositeService(Iterable<CompositeServiceEntry> services) {
        requireNonNull(services, "services");

        final List<CompositeServiceEntry> servicesCopy = new ArrayList<>();
        for (CompositeServiceEntry e : services) {
            servicesCopy.add(e);
            serviceMapping.add(e.pathMapping(), e.service());
        }

        this.services = Collections.unmodifiableList(servicesCopy);

        serviceMapping.freeze();
    }

    @Override
    public void serviceAdded(Server server) throws Exception {
        for (CompositeServiceEntry e : services()) {
            ServiceCallbackInvoker.invokeServiceAdded(server, e.service());
        }
    }

    /**
     * Returns the list of {@link CompositeServiceEntry}s added to this composite {@link Service}.
     */
    protected List<CompositeServiceEntry> services() {
        return services;
    }

    /**
     * Returns the {@code index}-th {@link Service} in this composite {@link Service}. The index of the
     * {@link Service} added first is {@code 0}, and so on.
     */
    @SuppressWarnings("unchecked")
    protected <T extends Service> T serviceAt(int index) {
        return (T) services().get(index).service();
    }

    /**
     * Finds the {@link Service} whose {@link PathMapping} matches the {@code path}.
     *
     * @return the {@link Service} wrapped by {@link MappedService} if there's a match.
     *         {@link MappedService#empty()} if there's no match.
     */
    protected MappedService findService(String path) {
        return serviceMapping.apply(path);
    }

    @Override
    public final ServiceCodec codec() {
        return codec;
    }

    @Override
    public final ServiceInvocationHandler handler() {
        return handler;
    }

    private final class CompositeServiceCodec implements ServiceCodec {
        @Override
        public void codecAdded(Server server) throws Exception {
            for (CompositeServiceEntry e : services()) {
                ServiceCallbackInvoker.invokeCodecAdded(server, e.service().codec());
            }
        }

        @Override
        public DecodeResult decodeRequest(
                Channel ch, SessionProtocol sessionProtocol, String hostname, String path,
                String mappedPath, ByteBuf in, Object origReq, Promise<Object> promise) throws Exception {

            final MappedService service = findService(mappedPath);
            if (!service.isPresent()) {
                return DecodeResult.NOT_FOUND;
            }

            final DecodeResult result = service.codec().decodeRequest(
                    ch, sessionProtocol, hostname, path, service.mappedPath(), in, origReq, promise);

            if (result.type() == DecodeResultType.SUCCESS) {
                ServiceInvocationContext ctx = result.invocationContext();
                ctx.attr(MAPPED_SERVICE).set(service);
            }

            return result;
        }

        @Override
        public boolean failureResponseFailsSession(ServiceInvocationContext ctx) {
            return ctx.attr(MAPPED_SERVICE).get().codec().failureResponseFailsSession(ctx);
        }

        @Override
        public ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception {
            return ctx.attr(MAPPED_SERVICE).get().codec().encodeResponse(ctx, response);
        }

        @Override
        public ByteBuf encodeFailureResponse(ServiceInvocationContext ctx, Throwable cause) throws Exception {
            return ctx.attr(MAPPED_SERVICE).get().codec().encodeFailureResponse(ctx, cause);
        }
    }

    private final class CompositeServiceInvocationHandler implements ServiceInvocationHandler {
        @Override
        public void handlerAdded(Server server) throws Exception {
            for (CompositeServiceEntry e : services()) {
                ServiceCallbackInvoker.invokeHandlerAdded(server, e.service().handler());
            }
        }

        @Override
        public void invoke(ServiceInvocationContext ctx, Executor blockingTaskExecutor, Promise<Object> promise)
                throws Exception {
            ctx.attr(MAPPED_SERVICE).get().handler().invoke(ctx, blockingTaskExecutor, promise);
        }
    }
}
