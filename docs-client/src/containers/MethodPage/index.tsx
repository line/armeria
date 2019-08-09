/*
 * Copyright 2018 LINE Corporation
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

import 'react-dropdown/style.css';
import './style-dropdown.css';

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableRow from '@material-ui/core/TableRow';
import Typography from '@material-ui/core/Typography';
import React from 'react';
import { Option } from 'react-dropdown';
import { RouteComponentProps } from 'react-router-dom';

import {
  Method,
  Service,
  simpleName,
  Specification,
} from '../../lib/specification';
import { TRANSPORTS } from '../../lib/transports';
import { ANNOTATED_HTTP_MIME_TYPE } from '../../lib/transports/annotated-http';

import Section from '../../components/Section';
import VariableList from '../../components/VariableList';
import DebugPage from './DebugPage';
import Endpoints from './Endpoints';
import Exceptions from './Exceptions';

interface OwnProps {
  specification: Specification;
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
    (m) => m.name === props.match.params.methodName,
  );
  if (!method) {
    return <>Not found.</>;
  }

  const debugTransport = TRANSPORTS.getDebugTransport(method);
  const isAnnotatedHttpService =
    debugTransport !== undefined &&
    debugTransport.supportsMimeType(ANNOTATED_HTTP_MIME_TYPE);

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
          hasLocation={isAnnotatedHttpService}
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
      {!isAnnotatedHttpService && (
        <Exceptions method={method} specification={props.specification} />
      )}
      <Endpoints method={method} />
      {debugTransport && (
        <DebugPage
          {...props}
          method={method}
          isAnnotatedHttpService={isAnnotatedHttpService}
          exampleHeaders={getExampleHeaders(
            props.specification,
            service,
            method,
          )}
          exactPathMapping={
            isAnnotatedHttpService ? isExactPathMapping(method) : false
          }
          useRequestBody={useRequestBody(props.match.params.httpMethod)}
        />
      )}
    </>
  );
};

function getExampleHeaders(
  specification: Specification,
  service: Service,
  method: Method,
): Option[] {
  const exampleHeaders: Option[] = [];
  addExampleHeadersIfExists(exampleHeaders, method.exampleHttpHeaders);
  addExampleHeadersIfExists(exampleHeaders, service.exampleHttpHeaders);
  addExampleHeadersIfExists(
    exampleHeaders,
    specification.getExampleHttpHeaders(),
  );
  return exampleHeaders;
}

function addExampleHeadersIfExists(
  dst: Option[],
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

function removeBrackets(headers: string): string {
  const length = headers.length;
  return headers.substring(1, length - 1).trim();
}

function isExactPathMapping(method: Method): boolean {
  const endpoints = method.endpoints;
  if (endpoints.length !== 1) {
    throw new Error(`
    Endpoints size should be 1 to determine prefix or regex. size: ${endpoints.length}`);
  }
  const endpoint = endpoints[0];
  return endpoint.pathMapping.startsWith('exact:');
}

function useRequestBody(httpMethod: string) {
  return httpMethod === 'POST' || httpMethod === 'PUT';
}

export default React.memo(MethodPage);
