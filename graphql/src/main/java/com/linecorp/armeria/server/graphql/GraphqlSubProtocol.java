package com.linecorp.armeria.server.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import graphql.ExecutionResult;
import graphql.GraphQLError;

import java.util.List;

interface GraphqlSubProtocol {
    void sendResult(String operationId, ExecutionResult executionResult) throws JsonProcessingException;
    void sendGraphqlErrors(List<GraphQLError> errors) throws JsonProcessingException;
    void sendError(Throwable t) throws JsonProcessingException;
}
