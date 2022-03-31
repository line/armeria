package com.linecorp.armeria.internal.client.thrifty

import com.linecorp.armeria.client.ClientBuilderParams
import com.linecorp.armeria.client.RpcClient
import com.linecorp.armeria.client.UserClient
import com.linecorp.armeria.client.thrift.THttpClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.RpcRequest
import com.linecorp.armeria.common.RpcResponse
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil
import com.linecorp.armeria.internal.common.PathAndQuery
import com.microsoft.thrifty.ThriftException
import io.micrometer.core.instrument.MeterRegistry
import java.util.function.BiFunction

fun decodeException(cause: Throwable, nothing: Nothing?): Throwable {
    TODO("Not yet implemented")
}

internal class DefaultThriftyHttpClient(
    params: ClientBuilderParams,
    delegate: RpcClient,
    meterRegistry: MeterRegistry
) : UserClient<RpcRequest, RpcResponse>(
    params, delegate, meterRegistry, RpcResponse::from,
    BiFunction { _, cause -> RpcResponse.ofFailure(decodeException(cause, null)) }
), THttpClient {
    override fun unwrap(): RpcClient = (super.unwrap() as RpcClient)

    override fun execute(path: String, serviceType: Class<*>, method: String, vararg args: Any?): RpcResponse =
        execute0(path, serviceType, null, method, args)

    private fun execute0(
        path: String,
        serviceType: Class<*>,
        serviceName: String?,
        method: String,
        args: Array<out Any?>
    ): RpcResponse {
        val newPath = ArmeriaHttpUtil.concatPaths(uri().rawPath, path)
        val pathAndQuery = PathAndQuery.parse(newPath)
            ?: return RpcResponse.ofFailure(ThriftException(ThriftException.Kind.UNKNOWN_METHOD, "unknown path $path"))
        // A thrift path is always good to cache as it cannot have non-fixed parameters.
        pathAndQuery.storeInCache(newPath)
        val call = RpcRequest.of(serviceType, method, *args)
        return execute(
            scheme().sessionProtocol(), HttpMethod.POST,
            pathAndQuery.path(), null, serviceName, call
        )
    }

    override fun executeMultiplexed(
        path: String,
        serviceType: Class<*>,
        serviceName: String,
        method: String,
        vararg args: Any?
    ): RpcResponse = execute0(path, serviceType, serviceName, method, args)

}