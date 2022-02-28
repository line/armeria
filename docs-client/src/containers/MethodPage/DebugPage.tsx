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

import Alert from '@material-ui/lab/Alert';
import Button from '@material-ui/core/Button';
import Grid from '@material-ui/core/Grid';
import IconButton from '@material-ui/core/IconButton';
import Snackbar from '@material-ui/core/Snackbar';
import Tooltip from '@material-ui/core/Tooltip';
import Typography from '@material-ui/core/Typography';
import CloseIcon from '@material-ui/icons/Close';
import DeleteSweepIcon from '@material-ui/icons/DeleteSweep';
import FileCopyIcon from '@material-ui/icons/FileCopy';
import React, {
  ChangeEvent,
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useState,
} from 'react';
import { Light as SyntaxHighlighter } from 'react-syntax-highlighter';
import githubGist from 'react-syntax-highlighter/dist/esm/styles/hljs/github-gist';
import json from 'react-syntax-highlighter/dist/esm/languages/hljs/json';

import jsonMinify from 'jsonminify';
import { RouteComponentProps } from 'react-router';
import Section from '../../components/Section';
import { docServiceDebug } from '../../lib/header-provider';
import jsonPrettify from '../../lib/json-prettify';
import { Method } from '../../lib/specification';
import { TRANSPORTS } from '../../lib/transports';
import { SelectOption } from '../../lib/types';
import EndpointPath from './EndpointPath';
import HttpHeaders from './HttpHeaders';
import HttpQueryString from './HttpQueryString';
import RequestBody from './RequestBody';
import GraphqlRequestBody from './GraphqlRequestBody';

SyntaxHighlighter.registerLanguage('json', json);

interface OwnProps {
  method: Method;
  isAnnotatedService: boolean;
  isGraphqlService: boolean;
  exampleHeaders: SelectOption[];
  examplePaths: SelectOption[];
  exampleQueries: SelectOption[];
  exactPathMapping: boolean;
  useRequestBody: boolean;
}

type Props = OwnProps & RouteComponentProps;

const validateJsonObject = (jsonObject: string, description: string) => {
  let parsedJson;
  try {
    parsedJson = JSON.parse(jsonObject);
  } catch (e) {
    throw new Error(
      `Failed to parse a JSON object in the ${description}:\n${e}`,
    );
  }
  if (typeof parsedJson !== 'object') {
    throw new Error(
      `The ${description} must be a JSON object.\nYou entered: ${typeof parsedJson}`,
    );
  }
};

const copyTextToClipboard = (text: string) => {
  const textArea = document.createElement('textarea');
  textArea.style.opacity = '0.0';
  textArea.value = text;
  document.body.appendChild(textArea);
  textArea.focus();
  textArea.select();
  document.execCommand('copy');
  document.body.removeChild(textArea);
};

const scrollToDebugForm = () => {
  const scrollNode = document.getElementById('debug-form');
  if (scrollNode) {
    scrollNode.scrollIntoView({ behavior: 'smooth' });
  }
};

const toggle = (prev: boolean, override: unknown) => {
  if (typeof override === 'boolean') {
    return override;
  }
  return !prev;
};

const escapeSingleQuote = (text: string) => text.replace(/'/g, `'\\''`);

const extractUrlPath = (method: Method) => {
  const endpoints = method.endpoints;
  return endpoints[0].pathMapping.substring('exact:'.length);
};

const DebugPage: React.FunctionComponent<Props> = ({
  exactPathMapping,
  exampleHeaders,
  examplePaths,
  exampleQueries,
  isAnnotatedService,
  isGraphqlService,
  history,
  location,
  match,
  method,
  useRequestBody,
}) => {
  const [requestBodyOpen, toggleRequestBodyOpen] = useReducer(toggle, true);
  const [requestBody, setRequestBody] = useState('');
  const [debugResponse, setDebugResponse] = useState('');
  const [additionalQueriesOpen, toggleAdditionalQueriesOpen] = useReducer(
    toggle,
    true,
  );
  const [additionalQueries, setAdditionalQueries] = useState('');
  const [endpointPathOpen, toggleEndpointPathOpen] = useReducer(toggle, true);
  const [additionalPath, setAdditionalPath] = useState('');
  const [additionalHeadersOpen, toggleAdditionalHeadersOpen] = useReducer(
    toggle,
    true,
  );
  const [additionalHeaders, setAdditionalHeaders] = useState('');
  const [stickyHeaders, toggleStickyHeaders] = useReducer(toggle, false);
  const [snackbarOpen, setSnackbarOpen] = useState(false);
  const [snackbarMessage, setSnackbarMessage] = useState('');
  const [keepDebugResponse, toggleKeepDebugResponse] = useReducer(
    toggle,
    false,
  );

  const transport = TRANSPORTS.getDebugTransport(method);
  if (!transport) {
    throw new Error("This method doesn't have a debug transport.");
  }

  useEffect(() => {
    const urlParams = new URLSearchParams(location.search);

    let urlRequestBody;
    if (useRequestBody) {
      if (urlParams.has('request_body')) {
        if (!isGraphqlService) {
          urlRequestBody = jsonPrettify(urlParams.get('request_body')!);
        } else {
          urlRequestBody = urlParams.get('request_body');
        }
        scrollToDebugForm();
      }
    }

    let urlPath;
    if (isAnnotatedService || isGraphqlService) {
      if (exactPathMapping) {
        urlPath = extractUrlPath(method);
      } else {
        urlPath = urlParams.get('endpoint_path') || '';
      }
    } else {
      urlPath =
        transport.findDebugMimeTypeEndpoint(
          method,
          urlParams.get('endpoint_path') || undefined,
        )?.pathMapping || '';
    }

    const urlQueries = isAnnotatedService ? urlParams.get('queries') : '';

    if (!keepDebugResponse) {
      setDebugResponse('');
      toggleKeepDebugResponse(false);
    }
    setSnackbarOpen(false);
    setRequestBody(urlRequestBody || method.exampleRequests[0] || '');
    setAdditionalPath(urlPath || '');
    setAdditionalQueries(urlQueries || '');
  }, [
    exactPathMapping,
    exampleQueries.length,
    isAnnotatedService,
    isGraphqlService,
    location.search,
    match.params,
    method,
    transport,
    useRequestBody,
    keepDebugResponse,
  ]);

  /* eslint-disable react-hooks/exhaustive-deps */
  useEffect(() => {
    const urlParams = new URLSearchParams(location.search);

    if (urlParams.has('sticky_headers')) {
      toggleStickyHeaders(true);
    }

    let headers = urlParams.has('headers')
      ? jsonPrettify(urlParams.get('headers')!)
      : undefined;

    if (!headers) {
      headers = stickyHeaders ? additionalHeaders : '';
    }
    setAdditionalHeaders(headers);
  }, [match.params]);
  /* eslint-enable react-hooks/exhaustive-deps */

  const showSnackbar = useCallback((text: string) => {
    setSnackbarOpen(true);
    setSnackbarMessage(text);
  }, []);

  const dismissSnackbar = useCallback(() => {
    setSnackbarOpen(false);
  }, []);

  const onSelectedQueriesChange = useCallback(
    (e: ChangeEvent<{ value: unknown }>) => {
      setAdditionalQueries(e.target.value as string);
    },
    [],
  );

  const onQueriesFormChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setAdditionalQueries(e.target.value);
    },
    [],
  );

  const onSelectedPathChange = useCallback(
    (e: ChangeEvent<{ value: unknown }>) => {
      setAdditionalPath(e.target.value as string);
    },
    [],
  );

  const onPathFormChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    setAdditionalPath(e.target.value);
  }, []);

  const onSelectedHeadersChange = useCallback(
    (e: ChangeEvent<{ value: unknown }>) => {
      setAdditionalHeaders(e.target.value as string);
    },
    [],
  );

  const onHeadersFormChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setAdditionalHeaders(e.target.value);
    },
    [],
  );

  const onDebugFormChange = useCallback((value: string) => {
    setRequestBody(value);
  }, []);

  const onExport = useCallback(() => {
    try {
      if (useRequestBody) {
        validateJsonObject(requestBody, 'request body');
      }

      if (additionalHeaders) {
        validateJsonObject(additionalHeaders, 'headers');
      }

      const headers =
        (additionalHeaders && JSON.parse(additionalHeaders)) || {};

      // window.location.origin may have compatibility issue
      // https://developer.mozilla.org/en-US/docs/Web/API/Window/location#Browser_compatibility
      const host =
        `${window.location.protocol}//${window.location.hostname}` +
        `${window.location.port ? `:${window.location.port}` : ''}`;

      const httpMethod = method.httpMethod;
      let uri;
      let endpoint;

      if (isAnnotatedService || isGraphqlService) {
        const queries = additionalQueries;
        if (exactPathMapping) {
          endpoint = transport.getDebugMimeTypeEndpoint(method);
          uri =
            `'${host}${escapeSingleQuote(
              endpoint.pathMapping.substring('exact:'.length),
            )}` +
            `${queries.length > 0 ? `?${escapeSingleQuote(queries)}` : ''}'`;
        } else {
          endpoint = transport.getDebugMimeTypeEndpoint(method, additionalPath);
          uri =
            `'${host}${escapeSingleQuote(additionalPath)}'` +
            `${queries.length > 0 ? `?${escapeSingleQuote(queries)}` : ''}'`;
        }
      } else if (additionalPath.length > 0) {
        endpoint = transport.getDebugMimeTypeEndpoint(method, additionalPath);
        uri = `'${host}${escapeSingleQuote(additionalPath)}'`;
      } else {
        endpoint = transport.getDebugMimeTypeEndpoint(method);
        uri = `'${host}${escapeSingleQuote(endpoint.pathMapping)}'`;
      }

      const body = transport.getCurlBody(
        endpoint,
        method,
        escapeSingleQuote(requestBody),
      );

      headers['content-type'] = transport.getDebugMimeType();
      if (process.env.WEBPACK_DEV === 'true') {
        headers[docServiceDebug] = 'true';
      }
      if (isGraphqlService) {
        headers.Accept = 'application/json';
      }

      const headerOptions = Object.keys(headers)
        .map((name) => {
          return `-H '${name}: ${headers[name]}'`;
        })
        .join(' ');

      const curlCommand =
        `curl -X${httpMethod} ${headerOptions} ${uri}` +
        `${useRequestBody ? ` -d '${body}'` : ''}`;

      copyTextToClipboard(curlCommand);
      showSnackbar('The curl command has been copied to the clipboard.');
    } catch (e) {
      if (e instanceof Object) {
        setDebugResponse(e.toString());
      } else {
        setDebugResponse('<unknown>');
      }
    }
  }, [
    useRequestBody,
    additionalHeaders,
    method,
    transport,
    requestBody,
    isAnnotatedService,
    isGraphqlService,
    showSnackbar,
    additionalQueries,
    exactPathMapping,
    additionalPath,
  ]);

  const onCopy = useCallback(() => {
    const response = debugResponse;
    if (response.length > 0) {
      copyTextToClipboard(response);
      showSnackbar('The response has been copied to the clipboard.');
    }
  }, [debugResponse, showSnackbar]);

  const onClear = useCallback(() => {
    setDebugResponse('');
  }, []);

  const executeRequest = useCallback(
    async (params: URLSearchParams) => {
      let executedRequestBody;
      if (useRequestBody) {
        executedRequestBody = params.get('request_body');
        if (!executedRequestBody) {
          return;
        }
      }

      let queries;
      let executedEndpointPath;
      if (isAnnotatedService) {
        queries = params.get('queries') || '';
        if (!exactPathMapping) {
          executedEndpointPath = params.get('endpoint_path') || undefined;
        }
      } else {
        executedEndpointPath = params.get('endpoint_path') || undefined;
      }

      const headersText = params.get('headers');
      const headers = headersText ? JSON.parse(headersText) : {};

      let executedDebugResponse;
      try {
        executedDebugResponse = await transport.send(
          method,
          headers,
          executedRequestBody,
          executedEndpointPath,
          queries,
        );
      } catch (e) {
        if (e instanceof Object) {
          executedDebugResponse = e.toString();
        } else {
          executedDebugResponse = '<unknown>';
        }
      }
      setDebugResponse(executedDebugResponse);
    },
    [useRequestBody, isAnnotatedService, exactPathMapping, method, transport],
  );

  const onSubmit = useCallback(async () => {
    setDebugResponse('');

    const queries = additionalQueries;
    const headers = additionalHeaders;
    const params = new URLSearchParams(location.search);

    try {
      if (useRequestBody) {
        // Validate requestBody only if it's not empty string.
        if (requestBody.trim()) {
          validateJsonObject(requestBody, 'request body');
        }

        // Do not round-trip through JSON.parse to minify the text so as to not lose numeric precision.
        // See: https://github.com/line/armeria/issues/273

        // For some reason jsonMinify minifies {} as empty string, so work around it.
        params.set('request_body', jsonMinify(requestBody) || '{}');
      }

      if (isAnnotatedService) {
        if (queries) {
          params.set('queries', queries);
        } else {
          params.delete('queries');
        }
        if (!exactPathMapping) {
          transport.getDebugMimeTypeEndpoint(method, additionalPath);
          params.set('endpoint_path', additionalPath);
        }
      } else if (additionalPath.length > 0) {
        params.set('endpoint_path', additionalPath);
      } else {
        // Fall back to default endpoint.
        params.delete('endpoint_path');
      }

      if (headers) {
        validateJsonObject(headers, 'HTTP headers');
        let minifiedHeaders = jsonMinify(headers);
        if (minifiedHeaders === '{}') {
          minifiedHeaders = '';
        }
        if (minifiedHeaders.length > 0) {
          params.set('headers', minifiedHeaders);
        } else {
          params.delete('headers');
        }
      } else {
        params.delete('headers');
      }
    } catch (e) {
      if (e instanceof Object) {
        setDebugResponse(e.toString());
      } else {
        setDebugResponse('<unknown>');
      }
      return;
    }

    if (stickyHeaders) {
      params.set('sticky_headers', 'true');
    } else {
      params.delete('sticky_headers');
    }

    const serializedParams = `?${params.toString()}`;
    if (serializedParams !== location.search) {
      // executeRequest may throw error before useEffect, we need to avoid useEffect cleanup the debug response.
      toggleKeepDebugResponse(true);
      history.push(`${location.pathname}${serializedParams}`);
    }
    await executeRequest(params);
  }, [
    additionalQueries,
    additionalHeaders,
    location.search,
    location.pathname,
    stickyHeaders,
    executeRequest,
    useRequestBody,
    isAnnotatedService,
    requestBody,
    exactPathMapping,
    additionalPath,
    history,
    method,
    transport,
  ]);

  const supportedExamplePaths = useMemo(() => {
    if (isAnnotatedService || isGraphqlService) {
      return examplePaths;
    }
    return transport.listDebugMimeTypeEndpoint(method).map((endpoint) => {
      return {
        label: endpoint.pathMapping,
        value: endpoint.pathMapping,
      };
    });
  }, [isAnnotatedService, isGraphqlService, transport, method, examplePaths]);

  return (
    <Section>
      <div id="debug-form">
        <Typography variant="body2" paragraph />
        <Grid container spacing={2}>
          <Grid item xs={12} sm={6}>
            <Typography variant="h6" paragraph>
              Debug
            </Typography>
            <Alert severity="info">
              You can set the default values by{' '}
              <a
                href="https://armeria.dev/docs/server-docservice/#example-requests-and-headers"
                rel="noreferrer"
                target="_blank"
              >
                specifying example requests and headers
              </a>
              .
            </Alert>
            <EndpointPath
              examplePaths={supportedExamplePaths}
              editable={!exactPathMapping}
              isAnnotatedService={isAnnotatedService}
              isGraphqlService={isGraphqlService}
              endpointPathOpen={endpointPathOpen}
              additionalPath={additionalPath}
              onEditEndpointPathClick={toggleEndpointPathOpen}
              onPathFormChange={onPathFormChange}
              onSelectedPathChange={onSelectedPathChange}
            />
            {isAnnotatedService && (
              <>
                <HttpQueryString
                  exampleQueries={exampleQueries}
                  additionalQueriesOpen={additionalQueriesOpen}
                  additionalQueries={additionalQueries}
                  onEditHttpQueriesClick={toggleAdditionalQueriesOpen}
                  onQueriesFormChange={onQueriesFormChange}
                  onSelectedQueriesChange={onSelectedQueriesChange}
                />
              </>
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
            {useRequestBody && (
              <>
                {isGraphqlService ? (
                  <GraphqlRequestBody
                    requestBodyOpen={requestBodyOpen}
                    requestBody={requestBody}
                    onEditRequestBodyClick={toggleRequestBodyOpen}
                    onDebugFormChange={onDebugFormChange}
                    schemaUrlPath={extractUrlPath(method)}
                  />
                ) : (
                  <RequestBody
                    requestBodyOpen={requestBodyOpen}
                    requestBody={requestBody}
                    onEditRequestBodyClick={toggleRequestBodyOpen}
                    onDebugFormChange={onDebugFormChange}
                  />
                )}
              </>
            )}
            <Typography variant="body2" paragraph />
            <Button variant="contained" color="primary" onClick={onSubmit}>
              Submit
            </Button>
            <Button variant="text" color="secondary" onClick={onExport}>
              Copy as a curl command
            </Button>
          </Grid>
          <Grid item xs={12} sm={6}>
            <Grid container spacing={1}>
              <Grid item xs="auto">
                <Tooltip title="Copy response">
                  <div>
                    <IconButton
                      onClick={onCopy}
                      disabled={debugResponse.length === 0}
                    >
                      <FileCopyIcon />
                    </IconButton>
                  </div>
                </Tooltip>
              </Grid>
              <Grid item xs="auto">
                <Tooltip title="Clear response">
                  <div>
                    <IconButton
                      onClick={onClear}
                      disabled={debugResponse.length === 0}
                    >
                      <DeleteSweepIcon />
                    </IconButton>
                  </div>
                </Tooltip>
              </Grid>
            </Grid>
            <SyntaxHighlighter
              language="json"
              style={githubGist}
              wrapLines={false}
            >
              {debugResponse}
            </SyntaxHighlighter>
          </Grid>
        </Grid>
        <Snackbar
          open={snackbarOpen}
          message={snackbarMessage}
          autoHideDuration={3000}
          onClose={dismissSnackbar}
          action={
            <IconButton color="inherit" onClick={dismissSnackbar}>
              <CloseIcon />
            </IconButton>
          }
        />
      </div>
    </Section>
  );
};

export default DebugPage;
