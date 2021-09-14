/*
 *  Copyright 2021 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.internal.common.util.ServiceNamingUtil;

/**
 * Generates the default name of a {@link Service} from its {@link ServiceRequestContext}.
 *
 * @see RequestOnlyLog#serviceName()
 * @see HttpService
 * @see RpcService
 */
@FunctionalInterface
public interface ServiceNaming {

    /**
     * Returns the {@link ServiceNaming} that always returns the given hard-coded service name.
     */
    static ServiceNaming of(String defaultServiceName) {
        requireNonNull(defaultServiceName, "defaultServiceName");
        return ctx -> defaultServiceName;
    }

    /**
     * Returns the {@link ServiceNaming} that returns the full name of an RPC stub class or
     * the innermost class from the given service. It will be fully qualified name including the package name
     * separated by a period. e.g. {@code com.foo.HelloService}.
     *
     * @see Class#getName()
     */
    static ServiceNaming fullTypeName() {
        return ctx -> {
            final RpcRequest rpcReq = ctx.rpcRequest();
            if (rpcReq != null) {
                return rpcReq.serviceName();
            }
            return ServiceNamingUtil.trimTrailingDollarSigns(
                    ServiceNamingUtil.fullTypeHttpServiceName(ctx.config().service()));
        };
    }

    /**
     * Returns the {@link ServiceNaming} that returns the simple name of an RPC stub class or
     * the innermost class from the given service. It is supposed to have a class name without a period.
     * e.g. {@code HelloService}.
     */
    static ServiceNaming simpleTypeName() {
        return SimpleTypeServiceNaming.INSTANCE;
    }

    /**
     * Returns the {@link ServiceNaming} that returns the shortened service name from the full name of an RPC
     * stub class or the innermost class from the given service. It follows Logback's abbreviation algorithm.
     * Please note that the rightmost segment in a service name is never abbreviated. For instance,
     * {@code com.foo.bar.HelloService} is able to be shorten to {@code c.f.b.HelloService}.
     *
     * @see <a href="http://logback.qos.ch/manual/layouts.html">Logback's abbreviation algorithm</a>
     */
    static ServiceNaming shorten(int targetLength) {
        return LengthBasedServiceNaming.of(targetLength);
    }

    /**
     * Returns the {@link ServiceNaming} that returns the shortened service name from the full name of an RPC
     * stub class or the innermost class from the given service. It follows Logback's abbreviation algorithm.
     * Please note that the rightmost segment in a service name is only left and other segments are
     * abbreviated to a letter. For instance, {@code com.foo.bar.HelloService} is able to be shorten to
     * {@code c.f.b.HelloService}.
     *
     * @see <a href="http://logback.qos.ch/manual/layouts.html">Logback's abbreviation algorithm</a>
     */
    static ServiceNaming shorten() {
        return shorten(0);
    }

    /**
     * Converts the specified {@linkplain RequestOnlyLog#serviceName() serviceName}
     * into another service name which is used as a meter tag or distributed trace's span name.
     *
     * <p>If {@code null} is returned, one of the following values will be used instead:
     * <ul>
     *   <li>gRPC - a service name (e.g, {@code com.foo.GrpcService})</li>
     *   <li>Thrift - a service type (e.g, {@code com.foo.ThriftService$AsyncIface} or
     *       {@code com.foo.ThriftService$Iface})</li>
     *   <li>{@link HttpService} and annotated service - an innermost class name</li>
     * </ul>
     *
     * <p>A naming rule can be set by either {@link ServerBuilder#defaultServiceNaming(ServiceNaming)} or
     * {@link ServiceBindingBuilder#defaultServiceNaming(ServiceNaming)}.
     * One of pre-defined naming rules is able to be used as follows.
     * <h4>Example</h4>
     * <pre>{@code
     * Server server = Server.builder()
     *                       .service(...)
     *                       .defaultServiceNaming(ServiceNaming.simpleTypeName())
     *                       .build()
     * }</pre>
     *
     * <h4>Example 2</h4>
     * <pre>{@code
     * Server server = Server.builder()
     *                       .route().path("/")
     *                       .defaultServiceNaming(ServiceNaming.fullTypeName())
     *                       ...
     *                       .build()
     * }</pre>
     *
     * <p>If customizing is needed out of given rules, a lambda expression would be applied as follows.
     * <pre>{@code
     * Server server = Server.builder()
     *                       .service(...)
     *                       .defaultServiceNaming(ctx -> {
     *                           final ServiceConfig config = ctx.config();
     *                           return config.server().defaultHostname() + config.route().patternString();
     *                       })
     *                       .build()
     * }</pre>
     */
    @Nullable
    String serviceName(ServiceRequestContext ctx);
}
