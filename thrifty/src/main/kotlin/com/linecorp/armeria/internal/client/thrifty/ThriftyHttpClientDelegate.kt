package com.linecorp.armeria.internal.client.thrifty


import com.linecorp.armeria.client.Client
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.DecoratingClient
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.client.RpcClient
import com.linecorp.armeria.common.CompletableRpcResponse
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RpcRequest
import com.linecorp.armeria.common.RpcResponse
import com.linecorp.armeria.common.SerializationFormat
import java.util.concurrent.atomic.AtomicInteger

class ThriftyHttpClientDelegate(
    httpClient: HttpClient,
    private val serializationFormat: SerializationFormat,
    private val nextSeqId: AtomicInteger = AtomicInteger(),
    val mediaType: MediaType = serializationFormat.mediaType()
) :
    DecoratingClient<HttpRequest, HttpResponse, RpcRequest, RpcResponse>(httpClient), RpcClient {

    override fun execute(ctx: ClientRequestContext, call: RpcRequest): RpcResponse {
        val setId = nextSeqId.incrementAndGet()
        val method = call.method()
        val args = call.params()
        val reply = CompletableRpcResponse()
        ctx.logBuilder().serializationFormat(serializationFormat)
        TODO("Not yet implemented")
    }
}
