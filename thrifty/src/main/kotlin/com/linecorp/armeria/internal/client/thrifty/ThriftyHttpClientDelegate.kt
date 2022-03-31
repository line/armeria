package com.linecorp.armeria.internal.client.thrifty

import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.client.RpcClient
import com.linecorp.armeria.common.RpcRequest
import com.linecorp.armeria.common.RpcResponse
import com.linecorp.armeria.common.SerializationFormat

class ThriftyHttpClientDelegate(newHttpClient: HttpClient, serializationFormat: SerializationFormat) : RpcClient {
    override fun execute(ctx: ClientRequestContext, req: RpcRequest): RpcResponse {
        TODO("Not yet implemented")
    }

}
