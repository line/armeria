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

import React, { ChangeEvent, Dispatch, useCallback, useReducer } from 'react';
import { extractUrlPath, Method, ServiceType } from '../../lib/specification';
import { SelectOption } from '../../lib/types';
import EndpointPath from './EndpointPath';
import HttpHeaders from './HttpHeaders';
import HttpQueryString from './HttpQueryString';
import RequestBody from './RequestBody';
import GraphqlRequestBody from './GraphqlRequestBody';

interface OwnProps {
  exactPathMapping: boolean;
  exampleHeaders: SelectOption[];
  supportedExamplePaths: SelectOption[];
  exampleQueries: SelectOption[];
  serviceType: ServiceType;
  method: Method;
  useRequestBody: boolean;
  requestBody: string;
  jsonSchemas: any[];
  setRequestBody: Dispatch<React.SetStateAction<string>>;
  additionalPath: string;
  setAdditionalPath: Dispatch<React.SetStateAction<string>>;
  additionalQueries: string;
  setAdditionalQueries: Dispatch<React.SetStateAction<string>>;
  additionalHeaders: string;
  setAdditionalHeaders: Dispatch<React.SetStateAction<string>>;
  stickyHeaders: boolean;
  toggleStickyHeaders: Dispatch<React.SetStateAction<unknown>>;
}

const toggle = (prev: boolean, override: unknown) => {
  if (typeof override === 'boolean') {
    return override;
  }
  return !prev;
};

const DebugInputs: React.FunctionComponent<OwnProps> = ({
  exactPathMapping,
  exampleHeaders,
  exampleQueries,
  serviceType,
  supportedExamplePaths,
  additionalHeaders,
  setAdditionalHeaders,
  additionalQueries,
  setAdditionalQueries,
  method,
  useRequestBody,
  additionalPath,
  setAdditionalPath,
  stickyHeaders,
  toggleStickyHeaders,
  requestBody,
  setRequestBody,
  jsonSchemas,
}) => {
  const [requestBodyOpen, toggleRequestBodyOpen] = useReducer(toggle, true);
  const [additionalQueriesOpen, toggleAdditionalQueriesOpen] = useReducer(
    toggle,
    true,
  );

  const [additionalHeadersOpen, toggleAdditionalHeadersOpen] = useReducer(
    toggle,
    true,
  );

  const [endpointPathOpen, toggleEndpointPathOpen] = useReducer(toggle, true);

  const onPathFormChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setAdditionalPath(e.target.value);
    },
    [setAdditionalPath],
  );

  const onSelectedPathChange = useCallback(
    (e: ChangeEvent<{ value: unknown }>) => {
      setAdditionalPath(e.target.value as string);
    },
    [setAdditionalPath],
  );

  const onQueriesFormChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setAdditionalQueries(e.target.value);
    },
    [setAdditionalQueries],
  );

  const onSelectedQueriesChange = useCallback(
    (e: ChangeEvent<{ value: unknown }>) => {
      setAdditionalQueries(e.target.value as string);
    },
    [setAdditionalQueries],
  );

  const onSelectedHeadersChange = useCallback(
    (e: ChangeEvent<{ value: unknown }>) => {
      setAdditionalHeaders(e.target.value as string);
    },
    [setAdditionalHeaders],
  );

  const onHeadersFormChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setAdditionalHeaders(e.target.value);
    },
    [setAdditionalHeaders],
  );

  const onDebugFormChange = useCallback(
    (value: string) => {
      setRequestBody(value);
    },
    [setRequestBody],
  );

  const onSelectedRequestBodyChange = useCallback(
    (e: ChangeEvent<{ value: unknown }>) => {
      setRequestBody(e.target.value as string);
    },
    [setRequestBody],
  );

  return (
    <>
      <EndpointPath
        examplePaths={supportedExamplePaths}
        editable={!exactPathMapping}
        serviceType={serviceType}
        endpointPathOpen={endpointPathOpen}
        additionalPath={additionalPath}
        onEditEndpointPathClick={toggleEndpointPathOpen}
        onPathFormChange={onPathFormChange}
        onSelectedPathChange={onSelectedPathChange}
      />
      {serviceType === ServiceType.HTTP && (
        <HttpQueryString
          exampleQueries={exampleQueries}
          additionalQueriesOpen={additionalQueriesOpen}
          additionalQueries={additionalQueries}
          onEditHttpQueriesClick={toggleAdditionalQueriesOpen}
          onQueriesFormChange={onQueriesFormChange}
          onSelectedQueriesChange={onSelectedQueriesChange}
        />
      )}
      <HttpHeaders
        exampleHeaders={exampleHeaders}
        additionalHeadersOpen={additionalHeadersOpen}
        additionalHeaders={additionalHeaders}
        stickyHeaders={stickyHeaders}
        onEditHttpHeadersClick={toggleAdditionalHeadersOpen}
        onSelectedHeadersChange={onSelectedHeadersChange}
        onHeadersFormChange={onHeadersFormChange}
        onStickyHeadersChange={toggleStickyHeaders}
      />
      {useRequestBody && serviceType === ServiceType.GRAPHQL ? (
        <GraphqlRequestBody
          requestBodyOpen={requestBodyOpen}
          requestBody={requestBody}
          onEditRequestBodyClick={toggleRequestBodyOpen}
          onDebugFormChange={onDebugFormChange}
          schemaUrlPath={extractUrlPath(method)}
        />
      ) : (
        <RequestBody
          exampleRequests={method.exampleRequests}
          onSelectedRequestBodyChange={onSelectedRequestBodyChange}
          requestBodyOpen={requestBodyOpen}
          requestBody={requestBody}
          onEditRequestBodyClick={toggleRequestBodyOpen}
          onDebugFormChange={onDebugFormChange}
          method={method}
          serviceType={serviceType}
          jsonSchemas={jsonSchemas}
        />
      )}
    </>
  );
};

export default React.memo(DebugInputs);
