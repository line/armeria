package com.linecorp.armeria.internal.server.annotation;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.annotation.ResponseConverterFunctionSelectorTest.TestClassToConvert;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider;

/**
 * For use with ResponseConverterFunctionSelectorTest
 */
public class TestSpiConverter implements ResponseConverterFunctionProvider {

  @Override
  public @Nullable ResponseConverterFunction createResponseConverterFunction(Type responseType,
      ResponseConverterFunction responseConverter) {
    final Class<?> responseClass = toClass(responseType);
    if(TestClassToConvert.class.isAssignableFrom(responseClass)) {
      return new TestResponseConverterFunction();
    } else {
      return null;
    }
  }

  private Class<?> toClass(Type type) {
    if(type instanceof ParameterizedType) {
      return (Class<?>) ((ParameterizedType) type).getRawType();
    } else if (type instanceof Class<?>) {
      return (Class<?>) type;
    } else {
      return null;
    }
  }

  static class TestResponseConverterFunction implements ResponseConverterFunction {
    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
        @Nullable Object result, HttpHeaders trailers) throws Exception {
      if(result instanceof TestClassToConvert) {
        return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, "testResponse");
      }
      return ResponseConverterFunction.fallthrough();
    }
  }
}
