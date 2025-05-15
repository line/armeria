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

import Typography from '@material-ui/core/Typography';
import React, { SetStateAction } from 'react';
import { RouteComponentProps } from 'react-router-dom';

import Button from '@material-ui/core/Button';
import { Launch } from '@material-ui/icons';
import Grid from '@material-ui/core/Grid';
import {
  Method,
  Service,
  ServiceType,
  simpleName,
  Specification,
} from '../../lib/specification';
import { TRANSPORTS } from '../../lib/transports';
import { ANNOTATED_HTTP_MIME_TYPE } from '../../lib/transports/annotated-http';
import { GRAPHQL_HTTP_MIME_TYPE } from '../../lib/transports/grahpql-http';
import { TTEXT_MIME_TYPE } from '../../lib/transports/thrift';
import { GRPC_UNFRAMED_MIME_TYPE } from '../../lib/transports/grpc-unframed';
import { SelectOption } from '../../lib/types';

import Section from '../../components/Section';
import VariableList from '../../components/VariableList';
import DebugPage from './DebugPage';
import Endpoints from './Endpoints';
import Exceptions from './Exceptions';
import Description from '../../components/Description';
import ReturnType from './ReturnType';

interface OwnProps {
  specification: Specification;
  jsonSchemas: any[];
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
  const [debugFormIsOpen, setDebugFormIsOpenState] = React.useState(false);

  const { location, history } = props;
  const setDebugFormIsOpen: React.Dispatch<SetStateAction<boolean>> =
    React.useCallback(
      (value) => {
        const valueToSet =
          value instanceof Function ? value(debugFormIsOpen) : value;
        const urlParams = new URLSearchParams(location.search);
        if (valueToSet === true) {
          urlParams.set('debug_form_is_open', `${valueToSet}`);
        } else {
          urlParams.delete('debug_form_is_open');
        }

        const serializedParams = `?${urlParams.toString()}`;
        if (serializedParams !== location.search) {
          history.push(`${location.pathname}${serializedParams}`);
        }

        return setDebugFormIsOpenState(valueToSet);
      },
      [
        debugFormIsOpen,
        setDebugFormIsOpenState,
        history,
        location.search,
        location.pathname,
      ],
    );

  const params = props.match.params;
  const service = props.specification.getServiceByName(params.serviceName);
  if (!service) {
    return <>Not found.</>;
  }
  const id = `${params.serviceName}/${params.methodName}/${params.httpMethod}`;
  const method = service.methods.find((m) => m.id === id);
  if (!method) {
    return <>Not found.</>;
  }
  const debugTransport = TRANSPORTS.getDebugTransport(method);

  let serviceType: ServiceType = ServiceType.UNKNOWN;

  if (debugTransport?.supportsMimeType(ANNOTATED_HTTP_MIME_TYPE)) {
    serviceType = ServiceType.HTTP;
  }

  if (debugTransport?.supportsMimeType(GRAPHQL_HTTP_MIME_TYPE)) {
    serviceType = ServiceType.GRAPHQL;
  }

  if (debugTransport?.supportsMimeType(GRPC_UNFRAMED_MIME_TYPE)) {
    serviceType = ServiceType.GRPC;
  }

  if (debugTransport?.supportsMimeType(TTEXT_MIME_TYPE)) {
    serviceType = ServiceType.THRIFT;
  }

  const parameterVariables = method.parameters.map((param) => {
    const childFieldInfos = props.specification.getStructByName(
      param.typeSignature,
    )?.fields;
    if (childFieldInfos) {
      return { ...param, childFieldInfos };
    }
    return param;
  });

  return (
    <>
      <Grid item container justifyContent="space-between">
        <Typography variant="h5" paragraph>
          <code>{`${simpleName(service.name)}.${method.name}()`}</code>
        </Typography>
        {debugTransport && (
          <Button
            variant="contained"
            color="primary"
            onClick={() => setDebugFormIsOpen(true)}
            endIcon={<Launch />}
            style={{ maxHeight: '3em' }}
          >
            Debug
          </Button>
        )}
      </Grid>
      {method.descriptionInfo?.docString && (
        <Section>
          <Description descriptionInfo={method.descriptionInfo} />
        </Section>
      )}
      <Section>
        <VariableList
          key={method.name}
          title="Parameters"
          variables={parameterVariables}
          specification={props.specification}
        />
      </Section>
      <ReturnType method={method} specification={props.specification} />
      {serviceType !== ServiceType.HTTP && (
        <Exceptions method={method} specification={props.specification} />
      )}
      <Endpoints method={method} />
      {debugTransport && (
        <DebugPage
          {...props}
          method={method}
          serviceType={serviceType}
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
            serviceType === ServiceType.HTTP ||
            serviceType === ServiceType.GRAPHQL
              ? isSingleExactPathMapping(method)
              : false
          }
          useRequestBody={needsToUseRequestBody(props.match.params.httpMethod)}
          debugFormIsOpen={debugFormIsOpen}
          setDebugFormIsOpen={setDebugFormIsOpen}
          docServiceRoute={props.specification.getDocServiceRoute()}
        />
      )}
    </>
  );
};

export default React.memo(MethodPage);
