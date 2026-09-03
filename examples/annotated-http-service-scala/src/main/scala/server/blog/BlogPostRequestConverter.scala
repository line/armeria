package server.blog

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.RequestConverterFunction

import java.lang.reflect.ParameterizedType
import java.util.concurrent.atomic.AtomicInteger

class BlogPostRequestConverter extends RequestConverterFunction:
  private val mapper = ObjectMapper()
  private val idGenerator = AtomicInteger()
  
  def stringValue(jsonNode: JsonNode, field: String): String =
    val value = jsonNode.get(field)
    if value == null then 
      throw new IllegalArgumentException(field + " is missing")
    else 
      value.textValue
  
  override def convertRequest(ctx: ServiceRequestContext, 
                              request: AggregatedHttpRequest, expectedResultType: Class[_], 
                              expectedParameterizedResultType: ParameterizedType): AnyRef = 
    if expectedResultType == classOf[BlogPost] then 
      val jsonNode = mapper.readTree(request.contentUtf8())
      val id = idGenerator.getAndIncrement()
      val title = stringValue(jsonNode, "title")
      val content = stringValue(jsonNode, "content")
      BlogPost(id, title, content)
    else 
      RequestConverterFunction.fallthrough()
