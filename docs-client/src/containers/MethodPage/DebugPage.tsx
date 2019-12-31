/*
 * Copyright 2019 LINE Corporation
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
  useReducer,
  useState,
} from 'react';
import { Option } from 'react-dropdown';
import SyntaxHighlighter from 'react-syntax-highlighter';
// react-syntax-highlighter type definitions are out of date.
// @ts-ignore
import githubGist from 'react-syntax-highlighter/dist/esm/styles/hljs/github-gist';

import jsonMinify from 'jsonminify';
import { RouteComponentProps } from 'react-router';
import Section from '../../components/Section';
import { docServiceDebug } from '../../lib/header-provider';
import jsonPrettify from '../../lib/json-prettify';
import { Method } from '../../lib/specification';
import { TRANSPORTS } from '../../lib/transports';
import EndpointPath from './EndpointPath';
import HttpHeaders from './HttpHeaders';
import HttpQueryString from './HttpQueryString';
import RequestBody from './RequestBody';

interface OwnProps {
  method: Method;
  isAnnotatedService: boolean;
  exampleHeaders: Option[];
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

const DebugPage: React.FunctionComponent<Props> = ({
  exactPathMapping,
  exampleHeaders,
  isAnnotatedService,
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
    false,
  );
  const [additionalQueries, setAdditionalQueries] = useState('');
  const [endpointPathOpen, toggleEndpointPathOpen] = useReducer(toggle, false);
  const [endpointPath, setEndpointPath] = useState('');
  const [additionalHeadersOpen, toggleAdditionalHeadersOpen] = useReducer(
    toggle,
    false,
  );
  const [additionalHeaders, setAdditionalHeaders] = useState('');
  const [stickyHeaders, toggleStickyHeaders] = useReducer(toggle, false);
  const [snackbarOpen, setSnackbarOpen] = useState(false);
  const [snackbarMessage, setSnackbarMessage] = useState('');

  useEffect(() => {
    const urlParams = new URLSearchParams(location.search);

    let urlRequestBody;
    if (useRequestBody) {
      if (urlParams.has('request_body')) {
        urlRequestBody = jsonPrettify(urlParams.get('request_body')!);
        scrollToDebugForm();
      }
    }

    const urlHeaders = urlParams.has('http_headers')
      ? jsonPrettify(urlParams.get('http_headers')!)
      : undefined;

    let urlQueries = '';
    let urlEndpointPath = '';
    if (isAnnotatedService) {
      if (exactPathMapping) {
        if (urlParams.has('queries')) {
          urlQueries = urlParams.get('queries')!;
        }
      } else if (urlParams.has('endpoint_path')) {
        urlEndpointPath = urlParams.get('endpoint_path')!;
      }
    }

    const stateHeaders = stickyHeaders ? additionalHeaders : undefined;

    const headersOpen = !!(urlHeaders || stateHeaders);

    setDebugResponse('');
    setSnackbarOpen(false);
    setRequestBody(urlRequestBody || method.exampleRequests[0] || '');
    setAdditionalHeaders(urlHeaders || stateHeaders || '');
    setAdditionalQueries(urlQueries);
    toggleAdditionalQueriesOpen(!!urlQueries);
    setEndpointPath(urlEndpointPath);
    toggleEndpointPathOpen(!!urlEndpointPath);
    toggleAdditionalHeadersOpen(headersOpen);

    if (urlParams.has('http_headers_sticky') && !stickyHeaders) {
      toggleStickyHeaders(undefined);
    }
  }, [match.params]);

  const showSnackbar = useCallback((text: string) => {
    setSnackbarOpen(true);
    setSnackbarMessage(text);
  }, []);

  const dismissSnackbar = useCallback(() => {
    setSnackbarOpen(false);
  }, []);

  const validateEndpointPath = useCallback(
    (newEndpointPath: string) => {
      if (!newEndpointPath) {
        throw new Error('You must specify the endpoint path.');
      }
      const endpoint = method.endpoints[0];
      const regexPathPrefix = endpoint.regexPathPrefix;
      const originalPath = endpoint.pathMapping;

      if (originalPath.startsWith('prefix:')) {
        // Prefix path mapping.
        const prefix = originalPath.substring('prefix:'.length);
        if (!newEndpointPath.startsWith(prefix)) {
          throw new Error(
            `The path: '${newEndpointPath}' should start with the prefix: ${prefix}`,
          );
        }
      }

      if (originalPath.startsWith('regex:')) {
        let regexPart;
        if (regexPathPrefix) {
          // Prefix adding path mapping.
          const prefix = regexPathPrefix.substring('prefix:'.length);
          if (!newEndpointPath.startsWith(prefix)) {
            throw new Error(
              `The path: '${newEndpointPath}' should start with the prefix: ${prefix}`,
            );
          }

          // Remove the prefix from the endpointPath so that we can test the regex.
          regexPart = newEndpointPath.substring(prefix.length - 1);
        } else {
          regexPart = newEndpointPath;
        }
        const regExp = new RegExp(originalPath.substring('regex:'.length));
        if (!regExp.test(regexPart)) {
          const expectedPath = regexPathPrefix
            ? `${regexPathPrefix} ${originalPath}`
            : originalPath;
          throw new Error(
            `Endpoint path: ${newEndpointPath} (expected: ${expectedPath})`,
          );
        }
      }
    },
    [method],
  );

  const onQueriesFormChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setAdditionalQueries(e.target.value);
    },
    [],
  );

  const onEndpointPathChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setEndpointPath(e.target.value);
    },
    [],
  );

  const onSelectedHeadersChange = useCallback((selectedHeaders: Option) => {
    setAdditionalHeaders(selectedHeaders.value);
  }, []);

  const onHeadersFormChange = useCallback(
    (e: ChangeEvent<HTMLInputElement>) => {
      setAdditionalHeaders(e.target.value);
    },
    [],
  );

  const onDebugFormChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    setRequestBody(e.target.value);
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

      const transport = TRANSPORTS.getDebugTransport(method);
      if (!transport) {
        throw new Error("This method doesn't have a debug transport.");
      }

      const httpMethod = method.httpMethod;
      const endpoint = transport.findDebugMimeTypeEndpoint(method);
      const path = endpoint.pathMapping;
      const body = transport.getCurlBody(
        endpoint,
        method,
        escapeSingleQuote(requestBody),
      );
      let uri;

      if (isAnnotatedService) {
        if (exactPathMapping) {
          const queries = additionalQueries;
          uri =
            `'${host}${escapeSingleQuote(path.substring('exact:'.length))}` +
            `${queries.length > 0 ? `?${escapeSingleQuote(queries)}` : ''}'`;
        } else {
          validateEndpointPath(endpointPath);
          uri = `'${host}${escapeSingleQuote(endpointPath)}'`;
        }
      } else {
        uri = `'${host}${escapeSingleQuote(path)}'`;
      }

      headers['content-type'] = transport.getDebugMimeType();
      if (process.env.WEBPACK_DEV === 'true') {
        headers[docServiceDebug] = 'true';
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
      setDebugResponse(e.toString());
    }
  }, [
    useRequestBody,
    requestBody,
    additionalHeaders,
    method,
    isAnnotatedService,
    exactPathMapping,
  ]);

  const onCopy = useCallback(() => {
    const response = debugResponse;
    if (response.length > 0) {
      copyTextToClipboard(response);
      showSnackbar('The response has been copied to the clipboard.');
    }
  }, [debugResponse]);

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
        if (exactPathMapping) {
          const queriesText = params.get('queries');
          queries = queriesText ? queriesText : '';
        } else {
          const endpointPathText = params.get('endpoint_path');
          executedEndpointPath = endpointPathText
            ? endpointPathText
            : undefined;
        }
      }

      const headersText = params.get('http_headers');
      const headers = headersText ? JSON.parse(headersText) : {};

      const transport = TRANSPORTS.getDebugTransport(method)!;
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
        executedDebugResponse = e.toString();
      }
      setDebugResponse(executedDebugResponse);
    },
    [useRequestBody, isAnnotatedService, exactPathMapping, method],
  );

  const onSubmit = useCallback(() => {
    setDebugResponse('');

    const queries = additionalQueries;
    const headers = additionalHeaders;
    const params = new URLSearchParams(location.search);

    try {
      if (useRequestBody) {
        // Validate requestBody only if it's not empty string.
        if (!!requestBody.trim()) {
          validateJsonObject(requestBody, 'request body');
        }

        // Do not round-trip through JSON.parse to minify the text so as to not lose numeric precision.
        // See: https://github.com/line/armeria/issues/273

        // For some reason jsonMinify minifies {} as empty string, so work around it.
        const minifiedRequestBody = jsonMinify(requestBody) || '{}';
        params.set('request_body', minifiedRequestBody);
      }

      if (isAnnotatedService) {
        if (exactPathMapping) {
          if (queries) {
            params.set('queries', queries);
          }
        } else {
          validateEndpointPath(endpointPath);
          params.set('endpoint_path', endpointPath);
        }
      }

      if (headers) {
        validateJsonObject(headers, 'HTTP headers');
        let minifiedHeaders = jsonMinify(headers);
        if (minifiedHeaders === '{}') {
          minifiedHeaders = '';
        }
        if (minifiedHeaders.length > 0) {
          params.set('http_headers', minifiedHeaders);
        }
      }
    } catch (e) {
      setDebugResponse(e.toString());
      return;
    }

    if (stickyHeaders) {
      params.set('http_headers_sticky', 'true');
    } else {
      params.delete('http_headers_sticky');
    }

    const serializedParams = `?${params.toString()}`;
    if (serializedParams !== location.search) {
      history.push(`${location.pathname}${serializedParams}`);
    }
    executeRequest(params);
  }, [
    requestBody,
    endpointPath,
    additionalQueries,
    additionalHeaders,
    location,
    useRequestBody,
    isAnnotatedService,
    exactPathMapping,
    validateEndpointPath,
    stickyHeaders,
    executeRequest,
  ]);

  return (
    <Section>
      <div id="debug-form">
        <Typography variant="body2" paragraph />
        <Grid container spacing={2}>
          <Grid item xs={12} sm={6}>
            <Typography variant="h6" paragraph>
              Debug
            </Typography>
            {isAnnotatedService &&
              (exactPathMapping ? (
                <>
                  <HttpQueryString
                    additionalQueriesOpen={additionalQueriesOpen}
                    additionalQueries={additionalQueries}
                    onEditHttpQueriesClick={toggleAdditionalQueriesOpen}
                    onQueriesFormChange={onQueriesFormChange}
                  />
                </>
              ) : (
                <EndpointPath
                  endpointPathOpen={endpointPathOpen}
                  endpointPath={endpointPath}
                  onEditEndpointPathClick={toggleEndpointPathOpen}
                  onEndpointPathChange={onEndpointPathChange}
                />
              ))}
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
                <RequestBody
                  requestBodyOpen={requestBodyOpen}
                  requestBody={requestBody}
                  onEditRequestBodyClick={toggleRequestBodyOpen}
                  onDebugFormChange={onDebugFormChange}
                />
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
