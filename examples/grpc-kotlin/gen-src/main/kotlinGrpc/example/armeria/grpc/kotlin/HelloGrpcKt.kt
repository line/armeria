package example.armeria.grpc.kotlin

import example.armeria.grpc.kotlin.HelloServiceGrpc.getServiceDescriptor
import io.grpc.CallOptions
import io.grpc.CallOptions.DEFAULT
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.ServerServiceDefinition.builder
import io.grpc.ServiceDescriptor
import io.grpc.Status
import io.grpc.Status.UNIMPLEMENTED
import io.grpc.StatusException
import io.grpc.kotlin.AbstractCoroutineServerImpl
import io.grpc.kotlin.AbstractCoroutineStub
import io.grpc.kotlin.ClientCalls
import io.grpc.kotlin.ClientCalls.bidiStreamingRpc
import io.grpc.kotlin.ClientCalls.clientStreamingRpc
import io.grpc.kotlin.ClientCalls.serverStreamingRpc
import io.grpc.kotlin.ClientCalls.unaryRpc
import io.grpc.kotlin.ServerCalls
import io.grpc.kotlin.ServerCalls.bidiStreamingServerMethodDefinition
import io.grpc.kotlin.ServerCalls.clientStreamingServerMethodDefinition
import io.grpc.kotlin.ServerCalls.serverStreamingServerMethodDefinition
import io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition
import io.grpc.kotlin.StubFor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow

/**
 * Holder for Kotlin coroutine-based client and server APIs for example.grpc.hello.HelloService.
 */
object HelloServiceGrpcKt {
  @JvmStatic
  val serviceDescriptor: ServiceDescriptor
    get() = HelloServiceGrpc.getServiceDescriptor()

  val helloMethod: MethodDescriptor<Hello.HelloRequest, Hello.HelloReply>
    @JvmStatic
    get() = HelloServiceGrpc.getHelloMethod()

  val lazyHelloMethod: MethodDescriptor<Hello.HelloRequest, Hello.HelloReply>
    @JvmStatic
    get() = HelloServiceGrpc.getLazyHelloMethod()

  val blockingHelloMethod: MethodDescriptor<Hello.HelloRequest, Hello.HelloReply>
    @JvmStatic
    get() = HelloServiceGrpc.getBlockingHelloMethod()

  val shortBlockingHelloMethod: MethodDescriptor<Hello.HelloRequest, Hello.HelloReply>
    @JvmStatic
    get() = HelloServiceGrpc.getShortBlockingHelloMethod()

  val lotsOfRepliesMethod: MethodDescriptor<Hello.HelloRequest, Hello.HelloReply>
    @JvmStatic
    get() = HelloServiceGrpc.getLotsOfRepliesMethod()

  val lotsOfGreetingsMethod: MethodDescriptor<Hello.HelloRequest, Hello.HelloReply>
    @JvmStatic
    get() = HelloServiceGrpc.getLotsOfGreetingsMethod()

  val bidiHelloMethod: MethodDescriptor<Hello.HelloRequest, Hello.HelloReply>
    @JvmStatic
    get() = HelloServiceGrpc.getBidiHelloMethod()

  /**
   * A stub for issuing RPCs to a(n) example.grpc.hello.HelloService service as suspending
   * coroutines.
   */
  @StubFor(HelloServiceGrpc::class)
  class HelloServiceCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT
  ) : AbstractCoroutineStub<HelloServiceCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): HelloServiceCoroutineStub =
        HelloServiceCoroutineStub(channel, callOptions)

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @return The single response from the server.
     */
    suspend fun hello(request: Hello.HelloRequest): Hello.HelloReply = unaryRpc(
      channel,
      HelloServiceGrpc.getHelloMethod(),
      request,
      callOptions,
      Metadata()
    )
    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @return The single response from the server.
     */
    suspend fun lazyHello(request: Hello.HelloRequest): Hello.HelloReply = unaryRpc(
      channel,
      HelloServiceGrpc.getLazyHelloMethod(),
      request,
      callOptions,
      Metadata()
    )
    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @return The single response from the server.
     */
    suspend fun blockingHello(request: Hello.HelloRequest): Hello.HelloReply = unaryRpc(
      channel,
      HelloServiceGrpc.getBlockingHelloMethod(),
      request,
      callOptions,
      Metadata()
    )
    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @return The single response from the server.
     */
    suspend fun shortBlockingHello(request: Hello.HelloRequest): Hello.HelloReply = unaryRpc(
      channel,
      HelloServiceGrpc.getShortBlockingHelloMethod(),
      request,
      callOptions,
      Metadata()
    )
    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    fun lotsOfReplies(request: Hello.HelloRequest): Flow<Hello.HelloReply> = serverStreamingRpc(
      channel,
      HelloServiceGrpc.getLotsOfRepliesMethod(),
      request,
      callOptions,
      Metadata()
    )
    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][Status].  If the RPC completes with another status, a corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * This function collects the [Flow] of requests.  If the server terminates the RPC
     * for any reason before collection of requests is complete, the collection of requests
     * will be cancelled.  If the collection of requests completes exceptionally for any other
     * reason, the RPC will be cancelled for that reason and this method will throw that
     * exception.
     *
     * @param requests A [Flow] of request messages.
     *
     * @return The single response from the server.
     */
    suspend fun lotsOfGreetings(requests: Flow<Hello.HelloRequest>): Hello.HelloReply =
        clientStreamingRpc(
      channel,
      HelloServiceGrpc.getLotsOfGreetingsMethod(),
      requests,
      callOptions,
      Metadata()
    )
    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * The [Flow] of requests is collected once each time the [Flow] of responses is
     * collected. If collection of the [Flow] of responses completes normally or
     * exceptionally before collection of `requests` completes, the collection of
     * `requests` is cancelled.  If the collection of `requests` completes
     * exceptionally for any other reason, then the collection of the [Flow] of responses
     * completes exceptionally for the same reason and the RPC is cancelled with that reason.
     *
     * @param requests A [Flow] of request messages.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    fun bidiHello(requests: Flow<Hello.HelloRequest>): Flow<Hello.HelloReply> = bidiStreamingRpc(
      channel,
      HelloServiceGrpc.getBidiHelloMethod(),
      requests,
      callOptions,
      Metadata()
    )}

  /**
   * Skeletal implementation of the example.grpc.hello.HelloService service based on Kotlin
   * coroutines.
   */
  abstract class HelloServiceCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for example.grpc.hello.HelloService.Hello.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC
     * will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    open suspend fun hello(request: Hello.HelloRequest): Hello.HelloReply = throw
        StatusException(UNIMPLEMENTED.withDescription("Method example.grpc.hello.HelloService.Hello is unimplemented"))

    /**
     * Returns the response to an RPC for example.grpc.hello.HelloService.LazyHello.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC
     * will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    open suspend fun lazyHello(request: Hello.HelloRequest): Hello.HelloReply = throw
        StatusException(UNIMPLEMENTED.withDescription("Method example.grpc.hello.HelloService.LazyHello is unimplemented"))

    /**
     * Returns the response to an RPC for example.grpc.hello.HelloService.BlockingHello.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC
     * will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    open suspend fun blockingHello(request: Hello.HelloRequest): Hello.HelloReply = throw
        StatusException(UNIMPLEMENTED.withDescription("Method example.grpc.hello.HelloService.BlockingHello is unimplemented"))

    /**
     * Returns the response to an RPC for example.grpc.hello.HelloService.ShortBlockingHello.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC
     * will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    open suspend fun shortBlockingHello(request: Hello.HelloRequest): Hello.HelloReply = throw
        StatusException(UNIMPLEMENTED.withDescription("Method example.grpc.hello.HelloService.ShortBlockingHello is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for example.grpc.hello.HelloService.LotsOfReplies.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    open fun lotsOfReplies(request: Hello.HelloRequest): Flow<Hello.HelloReply> = throw
        StatusException(UNIMPLEMENTED.withDescription("Method example.grpc.hello.HelloService.LotsOfReplies is unimplemented"))

    /**
     * Returns the response to an RPC for example.grpc.hello.HelloService.LotsOfGreetings.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [Status].  If this method fails with a [java.util.concurrent.CancellationException], the RPC
     * will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param requests A [Flow] of requests from the client.  This flow can be
     *        collected only once and throws [java.lang.IllegalStateException] on attempts to
     * collect
     *        it more than once.
     */
    open suspend fun lotsOfGreetings(requests: Flow<Hello.HelloRequest>): Hello.HelloReply = throw
        StatusException(UNIMPLEMENTED.withDescription("Method example.grpc.hello.HelloService.LotsOfGreetings is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for example.grpc.hello.HelloService.BidiHello.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param requests A [Flow] of requests from the client.  This flow can be
     *        collected only once and throws [java.lang.IllegalStateException] on attempts to
     * collect
     *        it more than once.
     */
    open fun bidiHello(requests: Flow<Hello.HelloRequest>): Flow<Hello.HelloReply> = throw
        StatusException(UNIMPLEMENTED.withDescription("Method example.grpc.hello.HelloService.BidiHello is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = HelloServiceGrpc.getHelloMethod(),
      implementation = ::hello
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = HelloServiceGrpc.getLazyHelloMethod(),
      implementation = ::lazyHello
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = HelloServiceGrpc.getBlockingHelloMethod(),
      implementation = ::blockingHello
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = HelloServiceGrpc.getShortBlockingHelloMethod(),
      implementation = ::shortBlockingHello
    ))
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = HelloServiceGrpc.getLotsOfRepliesMethod(),
      implementation = ::lotsOfReplies
    ))
      .addMethod(clientStreamingServerMethodDefinition(
      context = this.context,
      descriptor = HelloServiceGrpc.getLotsOfGreetingsMethod(),
      implementation = ::lotsOfGreetings
    ))
      .addMethod(bidiStreamingServerMethodDefinition(
      context = this.context,
      descriptor = HelloServiceGrpc.getBidiHelloMethod(),
      implementation = ::bidiHello
    )).build()
  }
}
