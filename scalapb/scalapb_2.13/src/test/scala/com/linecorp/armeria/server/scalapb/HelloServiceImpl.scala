/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.scalapb

import armeria.scalapb.hello.{HelloReply, HelloRequest, HelloServiceGrpc, SimpleOneof}
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.scalapb.HelloServiceImpl.{toMessage, _}
import io.grpc.stub.StreamObserver
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import monix.execution
import monix.execution.Ack.Continue
import monix.execution.Scheduler
import monix.reactive.Observable
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

class HelloServiceImpl extends HelloServiceGrpc.HelloService {

  override def hello(request: HelloRequest): Future[HelloReply] = {
    ServiceRequestContext.current()
    Future.successful(HelloReply(toMessage(request.name)))
  }

  override def lazyHello(request: HelloRequest): Future[HelloReply] = {
    ServiceRequestContext.current()
    for {
      _ <- delay(1000)(ServiceRequestContext.current().eventLoop())
      _ = ServiceRequestContext.current()
    } yield HelloReply(toMessage(request.name))
  }

  override def blockingHello(request: HelloRequest): Future[HelloReply] = {
    // Simulate a blocking API call.
    ServiceRequestContext.current()
    for {
      _ <- Future {
        ServiceRequestContext.current()
        Thread.sleep(3000)
      }(blockingContextAwareExecutionContext)
      _ = ServiceRequestContext.current()
    } yield HelloReply(toMessage(request.name))
  }

  override def lotsOfReplies(request: HelloRequest, responseObserver: StreamObserver[HelloReply]): Unit =
    Observable
      .interval(1.second)
      .take(5)
      .map { index =>
        ServiceRequestContext.current()
        s"Hello, ${request.name}! (sequence: ${index + 1})"
      }
      .subscribe(
        message => {
          ServiceRequestContext.current()
          responseObserver.onNext(HelloReply(message))
          Continue
        },
        cause => {
          ServiceRequestContext.current()
          responseObserver.onError(cause)
        },
        () => {
          ServiceRequestContext.current()
          responseObserver.onCompleted()
        }
      )

  override def lotsOfGreetings(responseObserver: StreamObserver[HelloReply]): StreamObserver[HelloRequest] =
    new StreamObserver[HelloRequest]() {
      val names: mutable.Buffer[String] = mutable.Buffer()

      override def onNext(value: HelloRequest): Unit = {
        ServiceRequestContext.current()
        names += value.name
      }

      override def onError(t: Throwable): Unit = {
        ServiceRequestContext.current()
        responseObserver.onError(t)
      }

      override def onCompleted(): Unit = {
        ServiceRequestContext.current()
        responseObserver.onNext(HelloReply(toMessage(names.mkString(", "))))
        responseObserver.onCompleted()
      }
    }

  override def bidiHello(responseObserver: StreamObserver[HelloReply]): StreamObserver[HelloRequest] =
    new StreamObserver[HelloRequest]() {
      override def onNext(value: HelloRequest): Unit = {
        ServiceRequestContext.current()
        // Respond to every request received.
        responseObserver.onNext(HelloReply(toMessage(value.name)))
      }

      override def onError(t: Throwable): Unit = {
        ServiceRequestContext.current()
        responseObserver.onError(t)
      }

      override def onCompleted(): Unit = {
        ServiceRequestContext.current()
        responseObserver.onCompleted()
      }
    }

  private def delay(duration: Int)(executor: ScheduledExecutorService): Future[Unit] = {
    val promise = Promise[Unit]()
    executor.schedule(() => promise.trySuccess(()), duration, TimeUnit.MILLISECONDS)
    promise.future
  }

  override def oneof(request: SimpleOneof): Future[SimpleOneof] =
    Future.successful(request)
}

object HelloServiceImpl {

  implicit def blockingContextAwareExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutor(ServiceRequestContext.current().blockingTaskExecutor())

  implicit def contextAwareScheduler: Scheduler =
    execution.Scheduler(ServiceRequestContext.current().eventLoop())

  def toMessage(name: String): String = s"Hello, $name!"
}
