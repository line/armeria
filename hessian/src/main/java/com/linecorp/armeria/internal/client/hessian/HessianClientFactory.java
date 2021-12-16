/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.internal.client.hessian;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import com.caucho.hessian.client.HessianMetaInfoAPI;
import com.caucho.hessian.client.HessianRuntimeException;
import com.caucho.hessian.io.AbstractHessianInput;
import com.caucho.hessian.io.AbstractHessianOutput;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianDebugInputStream;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.caucho.hessian.io.HessianRemoteObject;
import com.caucho.hessian.io.HessianRemoteResolver;
import com.caucho.hessian.io.SerializerFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.client.hessian.HessianClient;
import com.linecorp.armeria.client.hessian.HessianClientOptions;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * HessianClientFactory.
 *
 */
public class HessianClientFactory extends DecoratingClientFactory {

    private static final SerializationFormat HESSIAN = SerializationFormat.of("HESSIAN");

    private final ClassLoader classLoader;

    private final SerializerFactory serializerFactory;

    private final HessianRemoteResolver resolver;

    private static final Set<Scheme> SUPPORTED_SCHEMES;

    static {
        final ImmutableSet.Builder<Scheme> builder = ImmutableSet.builder();
        for (SessionProtocol p : SessionProtocol.values()) {
            builder.add(Scheme.of(HESSIAN, p));
        }
        SUPPORTED_SCHEMES = builder.build();
    }

    /**
     * Creates a new instance.
     */
    protected HessianClientFactory(ClientFactory delegate) {
        super(delegate);
        classLoader = Thread.currentThread().getContextClassLoader();
        serializerFactory = new SerializerFactory(classLoader);
        resolver = new HessianClientResolver();
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return SUPPORTED_SCHEMES;
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        validateParams(params);

        final Class<?> clientType = params.clientType();
        ClientOptions options = params.options();
        HttpClient httpClient = newHttpClient(params);
        if (httpClient.as(DecodingClient.class) == null) {
            options = options.toBuilder().decorator(DecodingClient.newDecorator()).build();
            params = clientBuilderParams(params, options);
            httpClient = newHttpClient(params);
        }
        final RpcClient delegate = options.decoration().rpcDecorate(
                new HessianHttpClientDelegate(httpClient, params.scheme().serializationFormat(), this,
                                              options));

        if (clientType == HessianClient.class) {
            return new DefaultHessianHttpClient(params, delegate, meterRegistry());
        }

        // Create a HessianClient without path.
        final ClientBuilderParams delegateParams = ClientBuilderParams.of(params.scheme(),
                                                                          params.endpointGroup(), "/",
                                                                          HessianClient.class, options);

        final HessianClient hessianClient = new DefaultHessianHttpClient(delegateParams, delegate,
                                                                         meterRegistry());

        return Proxy.newProxyInstance(clientType.getClassLoader(),
                                      new Class<?>[] { clientType, HessianRemoteObject.class },
                                      new HessianHttpClientInvocationHandler(params, hessianClient));
    }

    protected static ClientBuilderParams clientBuilderParams(ClientBuilderParams params,
                                                             ClientOptions newOptions) {
        final URI uri = params.uri();
        final ClientBuilderParams newParams;
        if (Clients.isUndefinedUri(uri)) {
            newParams = ClientBuilderParams.of(uri, params.clientType(), newOptions);
        } else {
            newParams = ClientBuilderParams.of(params.scheme(), params.endpointGroup(),
                                               params.absolutePathRef(),
                                               params.clientType(), newOptions);
        }
        return newParams;
    }

    /**
     * Creates a new proxy with the specified URL. The API class uses the java.api.class
     * value from _hessian_getAttribute
     * @param url the URL where the client object is located. The schema should be
     *        "hessian+http" or 'http', 'http' may be other protocol.
     * @return a proxy to the object with the specified interface.
     */
    public Object create(String url) throws ClassNotFoundException {
        final HessianMetaInfoAPI metaInfo = create(HessianMetaInfoAPI.class, url);

        final String apiClassName = (String) metaInfo._hessian_getAttribute("java.api.class");

        if (apiClassName == null) {
            throw new HessianRuntimeException(url + " has an unknown api.");
        }

      final   Class<?> apiClass = Class.forName(apiClassName, false, classLoader);

        return create(apiClass, url);
    }

    /**
     * Creates a new proxy with the specified URL. The returned object is a proxy with the
     * interface specified by api.
     *
     * <pre>
     * String url = "hessian+http://localhost:8080/ejb/hello");
     * HelloHome hello = (HelloHome) factory.create(HelloHome.class, url);
     * </pre>
     * @param api the interface the proxy class needs to implement
     * @param url the URL where the client object is located. The schema should be
     *        "hessian+http" or 'http', 'http' may be other protocol.
     * @return a proxy to the object with the specified interface.
     */
    public <T> T create(Class<T> api, String url) {
        return create(api, url, classLoader);
    }

    /**
     * Creates a new proxy with the specified URL. The returned object is a proxy with the
     * interface specified by api.
     *
     * <pre>
     * String url = "hessian+http://localhost:8080/ejb/hello");
     * HelloHome hello = (HelloHome) factory.create(HelloHome.class, url);
     * </pre>
     * @param api the interface the proxy class needs to implement
     * @param urlName the URL where the client object is located. The schema should be
     *        "hessian+http" or 'http', 'http' may be other protocol.
     * @return a proxy to the object with the specified interface.
     */
    public <T> T create(Class<T> api, String urlName, ClassLoader loader) {
     final    URI url;

        try {
            url = new URI(urlName);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        return create(api, url, loader);
    }

    /**
     * Creates a new proxy with the specified URL. The returned object is a proxy with the
     * interface specified by api.
     *
     * <pre>
     * String url = "hessian+http://localhost:8080/ejb/hello");
     * HelloHome hello = (HelloHome) factory.create(HelloHome.class, url);
     * </pre>
     * @param api the interface the proxy class needs to implement
     * @param url the URL where the client object is located. The schema should be
     *        "hessian+http" or 'http', 'http' may be other protocol.
     * @return a proxy to the object with the specified interface.
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> api, URI url, ClassLoader ignored) {
        if (api == null) {
            throw new NullPointerException("api must not be null for HessianProxyFactory.create()");
        }
        url = ensureHessianSchema(url);
        return (T) newClient(ClientBuilderParams.of(url, api, ClientOptions.of()));
    }

    /**
     * If uri schema not container '+' append hessian+ prefix. http://127.0.0.1/hello
     * would be hessian+http://127.0.0.1/hello
     * @param uri hessian service url.
     * @return uri with 'hessian+' prefix.
     */
    @VisibleForTesting
    static URI ensureHessianSchema(URI uri) {
        if (uri.getScheme().contains("+")) {
            return uri;
        }
        return URI.create("hessian+" + uri);
    }

    /**
     * Returns the remote resolver.
     */
    public HessianRemoteResolver getRemoteResolver() {
        return resolver;
    }

    /**
     * Gets the serializer factory.
     */
    public SerializerFactory getSerializerFactory() {
        return serializerFactory;
    }

    public AbstractHessianInput getHessianInput(InputStream is, ClientOptions options) {
        return getHessian2Input(is, options);
    }

    public AbstractHessianInput getHessian1Input(InputStream is, ClientOptions options) {
        final AbstractHessianInput in;

        if (options.get(HessianClientOptions.DEBUG)) {
            is = new HessianDebugInputStream(is, new PrintWriter(System.out));
        }

        in = new HessianInput(is);

        in.setRemoteResolver(getRemoteResolver());

        in.setSerializerFactory(getSerializerFactory());

        return in;
    }

    public AbstractHessianInput getHessian2Input(InputStream is, ClientOptions options) {
        final AbstractHessianInput in;

        if (options.get(HessianClientOptions.DEBUG)) {
            is = new HessianDebugInputStream(is, new PrintWriter(System.out));
        }

        in = new Hessian2Input(is);

        in.setRemoteResolver(getRemoteResolver());

        in.setSerializerFactory(getSerializerFactory());

        return in;
    }

    public AbstractHessianOutput getHessianOutput(OutputStream os, ClientOptions options) {
        final AbstractHessianOutput out;

        if (options.get(HessianClientOptions.HESSIAN2_REQUEST)) {
            out = new Hessian2Output(os);
        } else {
          final   HessianOutput out1 = new HessianOutput(os);
            out = out1;

            if (options.get(HessianClientOptions.HESSIAN2_REPLY)) {
                out1.setVersion(2);
            }
        }

        out.setSerializerFactory(getSerializerFactory());

        return out;
    }

    class HessianClientResolver implements HessianRemoteResolver {

        @Override
        public Object lookup(String type, String url) throws IOException {
         final    ClassLoader loader = Thread.currentThread().getContextClassLoader();

            try {
             final    Class<?> api = Class.forName(type, false, loader);

                return create(api, url);
            } catch (Exception e) {
                throw new IOException(String.valueOf(e), e);
            }
        }
    }
}
