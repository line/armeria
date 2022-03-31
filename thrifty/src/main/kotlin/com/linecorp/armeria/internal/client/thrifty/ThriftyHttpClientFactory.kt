package com.linecorp.armeria.internal.client.thrifty

import com.linecorp.armeria.client.ClientBuilderParams
import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.DecoratingClientFactory
import com.linecorp.armeria.client.thrift.THttpClient
import com.linecorp.armeria.common.Scheme
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.thrifty.ThriftySerializationFormats
import java.lang.reflect.Proxy

class ThriftyHttpClientFactory internal constructor(httpClientFactory: ClientFactory) :
    DecoratingClientFactory(httpClientFactory) {

    companion object {
        private val SUPPORTED_SCHEMES: Set<Scheme> = buildSet {
            ThriftySerializationFormats.values.forEach { f ->
                SessionProtocol.values().forEach { p ->
                    add(Scheme.of(f, p))
                }
            }
        }
    }

    override fun supportedSchemes(): Set<Scheme> {
        return SUPPORTED_SCHEMES
    }

    override fun newClient(params: ClientBuilderParams): Any {
        validateParams(params)
        val clientType = params.clientType()
        val options = params.options()
        val delegate = options.decoration()
            .rpcDecorate(ThriftyHttpClientDelegate(newHttpClient(params), params.scheme().serializationFormat()))

        if (clientType == THttpClient::class.java) {
            return DefaultThriftyHttpClient(params, delegate, meterRegistry())
        }

        // Create a THttpClient without path.
        val delegateParams = ClientBuilderParams.of(
            params.scheme(),
            params.endpointGroup(),
            "/", THttpClient::class.java,
            options
        )

        val thriftClient: THttpClient =
            DefaultThriftyHttpClient(delegateParams, delegate, meterRegistry())
        return Proxy.newProxyInstance(
            clientType.classLoader, arrayOf(clientType),
            ThriftyHttpClientInvocationHandler(params, thriftClient)
        )
    }
}