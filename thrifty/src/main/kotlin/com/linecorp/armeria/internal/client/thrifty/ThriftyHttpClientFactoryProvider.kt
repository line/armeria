package com.linecorp.armeria.internal.client.thrifty

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ClientFactoryProvider

/**
 * [ClientFactoryProvider] that creates a [ThriftyHttpClientFactory].
 */
class ThriftyHttpClientFactoryProvider:ClientFactoryProvider {
    override fun newFactory(httpClientFactory: ClientFactory): ClientFactory =
        ThriftyHttpClientFactory(httpClientFactory)
}
