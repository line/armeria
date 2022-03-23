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

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableRow from '@material-ui/core/TableRow';
import Typography from '@material-ui/core/Typography';
import React from 'react';
import { RouteComponentProps } from 'react-router-dom';

import {
  Method,
  Service,
  simpleName,
  Specification,
} from '../../lib/specification';
import { TRANSPORTS } from '../../lib/transports';
import { ANNOTATED_HTTP_MIME_TYPE } from '../../lib/transports/annotated-http';
import { GRAPHQL_HTTP_MIME_TYPE } from '../../lib/transports/grahpql-http';
import { SelectOption } from '../../lib/types';

import Section from '../../components/Section';
import VariableList from '../../components/VariableList';
import DebugPage from './DebugPage';
import Endpoints from './Endpoints';
import Exceptions from './Exceptions';

interface OwnProps {
  specification: Specification;
}

function getExampleHeaders(
  specification: Specification,
  service: Service,
  method: Method,
): SelectOption[] {
  const exampleHeaders: SelectOption[] = [];
  addExampleHeadersIfExists(exampleHeaders, method.exampleHeaders);
  addExampleHeadersIfExists(exampleHeaders, service.exampleHeaders);
  addExampleHeadersIfExists(exampleHeaders, specification.getExampleHeaders());
  return exampleHeaders;
}

function addExampleHeadersIfExists(
  dst: SelectOption[],
  src: { [name: string]: string }[],
) {
  if (src.length > 0) {
    for (const headers of src) {
      dst.push({
        value: JSON.stringify(headers, null, 2),
        label: removeBrackets(JSON.stringify(headers).trim()),
      });
    }
  }
}

function getExamplePaths(
  specification: Specification,
  service: Service,
  method: Method,
): SelectOption[] {
  return (
    specification
      .getServiceByName(service.name)
      ?.methods?.find((m) => m.name === method.name)
      ?.examplePaths?.map((path) => {
        return { label: path, value: path };
      }) || []
  );
}

function getExampleQueries(
  specification: Specification,
  service: Service,
  method: Method,
): SelectOption[] {
  return (
    specification
      .getServiceByName(service.name)
      ?.methods?.find((m) => m.name === method.name)
      ?.exampleQueries?.map((queries) => {
        return { label: queries, value: queries };
      }) || []
  );
}

function removeBrackets(headers: string): string {
  const length = headers.length;
  return headers.substring(1, length - 1).trim();
}

function isSingleExactPathMapping(method: Method): boolean {
  const endpoints = method.endpoints;
  return (
    method.endpoints.length === 1 &&
    endpoints[0].pathMapping.startsWith('exact:')
  );
}

const requestBodyAllowedHttpMethods: string[] = [
  'POST',
  'PUT',
  'PATCH',
  'DELETE',
];

function needsToUseRequestBody(httpMethod: string) {
  return requestBodyAllowedHttpMethods.includes(httpMethod);
}

type Props = OwnProps &
  RouteComponentProps<{
    serviceName: string;
    methodName: string;
    httpMethod: string;
  }>;

const MethodPage: React.FunctionComponent<Props> = (props) => {
  const service = props.specification.getServiceByName(
    props.match.params.serviceName,
  );
  if (!service) {
    return <>Not found.</>;
  }

  const method = service.methods.find(
    (m) =>
      m.name === props.match.params.methodName &&
      m.httpMethod === props.match.params.httpMethod,
  );
  if (!method) {
    return <>Not found.</>;
  }

  const debugTransport = TRANSPORTS.getDebugTransport(method);
  const isAnnotatedService =
    debugTransport !== undefined &&
    debugTransport.supportsMimeType(ANNOTATED_HTTP_MIME_TYPE);
  const isGraphqlService =
    debugTransport !== undefined &&
    debugTransport.supportsMimeType(GRAPHQL_HTTP_MIME_TYPE);

  return (
    <>
      <Typography variant="h5" paragraph>
        <code>{`${simpleName(service.name)}.${method.name}()`}</code>
      </Typography>
      <Typography variant="body2" paragraph>
        {method.docString}
      </Typography>
      <Section>
        <VariableList
          key={method.name}
          title="Parameters"
          variables={method.parameters}
          specification={props.specification}
        />
      </Section>
      <Section>
        <Typography variant="h6">Return Type</Typography>
        <Table>
          <TableBody>
            <TableRow>
              <TableCell>
                <code>
                  {props.specification.getTypeSignatureHtml(
                    method.returnTypeSignature,
                  )}
                </code>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </Section>
      {!isAnnotatedService && (
        <Exceptions method={method} specification={props.specification} />
      )}
      <Endpoints method={method} />
      {debugTransport && (
        <DebugPage
          {...props}
          method={method}
          isAnnotatedService={isAnnotatedService}
          isGraphqlService={isGraphqlService}
          exampleHeaders={getExampleHeaders(
            props.specification,
            service,
            method,
          )}
          examplePaths={getExamplePaths(props.specification, service, method)}
          exampleQueries={getExampleQueries(
            props.specification,
            service,
            method,
          )}
          exactPathMapping={
            isAnnotatedService || isGraphqlService
              ? isSingleExactPathMapping(method)
              : false
          }
          useRequestBody={needsToUseRequestBody(props.match.params.httpMethod)}
        />
      )}
    </>
  );
};

export default React.memo(MethodPage);
