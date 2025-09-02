package server.blog

import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.common.{HttpRequest, HttpResponse, HttpStatus}
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction

class BadRequestExceptionHandler extends ExceptionHandlerFunction :
  val mapper = ObjectMapper()

  override def handleException(ctx: ServiceRequestContext, req: HttpRequest, cause: Throwable): HttpResponse =
    if cause.isInstanceOf[IllegalArgumentException] then
      val message = cause.getMessage
      val objectNode = mapper.createObjectNode()
      objectNode.put("error", message)
      HttpResponse.ofJson(HttpStatus.BAD_REQUEST, objectNode)
    else
      ExceptionHandlerFunction.fallthrough()

