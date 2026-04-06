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

import Grid from '@material-ui/core/Grid';
import IconButton from '@material-ui/core/IconButton';
import Snackbar from '@material-ui/core/Snackbar';
import Tooltip from '@material-ui/core/Tooltip';
import Typography from '@material-ui/core/Typography';
import CloseIcon from '@material-ui/icons/Close';
import DeleteSweepIcon from '@material-ui/icons/DeleteSweep';
import FileCopyIcon from '@material-ui/icons/FileCopy';
import React, {
  Dispatch,
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
import {
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
} from '@material-ui/core';
import Button from '@material-ui/core/Button';
import Alert from '@material-ui/lab/Alert';
import { createStyles, makeStyles, Theme } from '@material-ui/core/styles';
import Section from '../../components/Section';
import { docServiceDebug } from '../../lib/header-provider';
import { jsonPrettify, validateJsonObject } from '../../lib/json-util';
import {
  extractUrlPath,
  Method,
  Route,
  RoutePathType,
  ServiceType,
} from '../../lib/specification';
import { TRANSPORTS } from '../../lib/transports';
import { ResponseData, SelectOption } from '../../lib/types';
import DebugInputs from './DebugInputs';

const useStyles = makeStyles((theme: Theme) =>
  createStyles({
    actionDialog: {
      justifyContent: 'space-between',
      margin: theme.spacing(1),
    },
    responseGrid: {
      borderLeft: `2px solid ${theme.palette.divider}`,
    },
  }),
);

SyntaxHighlighter.registerLanguage('json', json);

interface OwnProps {
  method: Method;
  serviceType: ServiceType;
  exampleHeaders: SelectOption[];
  examplePaths: SelectOption[];
  exampleQueries: SelectOption[];
  exactPathMapping: boolean;
  useRequestBody: boolean;
  debugFormIsOpen: boolean;
  setDebugFormIsOpen: Dispatch<React.SetStateAction<boolean>>;
  jsonSchemas: any;
  docServiceRoute?: Route;
}

type Props = OwnProps & RouteComponentProps;

const copyTextToClipboard = (text: string) => {
  const textArea = document.createElement('textarea');
  textArea.style.opacity = '0.0';
  textArea.value = text;

  const modal = document.getElementById('debug-form')!;
  modal.appendChild(textArea);

  textArea.focus();
  textArea.select();
  document.execCommand('copy');
  modal.removeChild(textArea);
};

const parseServerRootPath = (docServiceRoute: Route | undefined): string => {
  if (
    docServiceRoute === undefined ||
    docServiceRoute.pathType !== RoutePathType.PREFIX
  ) {
    return '';
  }

  // Remove '/*' from the path
  const docServicePath = docServiceRoute.patternString.slice(0, -2);
  const index = window.location.pathname.indexOf(docServicePath);
  if (index < 0) {
    return '';
  }

  return window.location.pathname.substring(0, index);
};

const toggle = (prev: boolean, override: unknown) => {
  if (typeof override === 'boolean') {
    return override;
  }
  return !prev;
};

const getStatusColor = (status?: number): string | undefined => {
  if (typeof status !== 'number') return undefined;
  return status >= 200 && status < 300 ? 'green' : 'red';
};

const ResponseStatusBar: React.FC<{
  responseMetaData: ResponseData | null;
}> = ({ responseMetaData }) => {
  if (!responseMetaData) {
    return null;
  }
  const color = getStatusColor(responseMetaData.status);
  return (
    <Grid
      item
      xs={12}
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        justifyContent: 'flex-start',
        alignItems: 'center',
        gap: '4px 16px',
        overflow: 'hidden',
        wordBreak: 'break-word',
      }}
    >
      <Typography
        variant="subtitle2"
        style={{
          display: 'flex',
          flexWrap: 'wrap',
        }}
      >
        <span style={{ marginRight: 16, color }}>
          <strong>Status</strong>: {responseMetaData.status ?? '–'}
        </span>
        <span style={{ marginRight: 16 }}>
          <strong>Duration</strong>: {responseMetaData.executionTime ?? '–'} ms
        </span>
        <span style={{ marginRight: 16 }}>
          <strong>Size</strong>: {responseMetaData.size ?? '–'} B
        </span>
        <span>
          <strong>Timestamp</strong> : {responseMetaData.timestamp ?? '-'}
        </span>
      </Typography>
    </Grid>
  );
};

const escapeSingleQuote = (text: string) => text.replace(/'/g, "'\\''");

const DebugPage: React.FunctionComponent<Props> = ({
  exactPathMapping,
  exampleHeaders,
  examplePaths,
  exampleQueries,
  serviceType,
  history,
  location,
  match,
  method,
  useRequestBody,
  debugFormIsOpen,
  setDebugFormIsOpen,
  jsonSchemas,
  docServiceRoute,
}) => {
  const [requestBody, setRequestBody] = useState('');
  const [additionalQueries, setAdditionalQueries] = useState('');
  const [responseData, setResponseData] = useState<ResponseData | null>(null);
  const [additionalPath, setAdditionalPath] = useState('');
  const [additionalHeaders, setAdditionalHeaders] = useState('');
  const [stickyHeaders, toggleStickyHeaders] = useReducer(toggle, false);
  const [snackbarOpen, setSnackbarOpen] = useState(false);
  const [snackbarMessage, setSnackbarMessage] = useState('');
  const [keepDebugResponse, toggleKeepDebugResponse] = useReducer(
    toggle,
    false,
  );

  const [currentApiId, setCurrentApiId] = useState<string>(method.id);
  const [responseCache, setResponseCache] = useState<
    Record<string, ResponseData>
  >({});

  const classes = useStyles();

  const transport = TRANSPORTS.getDebugTransport(method);
  if (!transport) {
    throw new Error("This method doesn't have a debug transport.");
  }

  useEffect(() => {
    const apiId = method.id;
    if (apiId !== currentApiId) {
      setCurrentApiId(apiId);
      if (responseCache[apiId]) {
        setResponseData(responseCache[apiId]);
      } else {
        setResponseData(null);
      }
    }
  }, [method, currentApiId, responseCache]);

  useEffect(() => {
    const urlParams = new URLSearchParams(location.search);

    let urlRequestBody = '';
    if (useRequestBody && urlParams.has('request_body')) {
      urlRequestBody = jsonPrettify(urlParams.get('request_body')!);
    }

    let urlDebugFormIsOpen = false;
    if (urlParams.has('debug_form_is_open')) {
      urlDebugFormIsOpen = urlParams.get('debug_form_is_open') === 'true';
    }

    let urlPath;
    if (
      serviceType === ServiceType.HTTP ||
      serviceType === ServiceType.GRAPHQL
    ) {
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

    const urlQueries =
      serviceType === ServiceType.HTTP ? urlParams.get('queries') ?? '' : '';

    if (!keepDebugResponse) {
      setResponseData(null);
      toggleKeepDebugResponse(false);
    }
    setSnackbarOpen(false);
    setRequestBody(urlRequestBody || method.exampleRequests[0] || '');
    setAdditionalPath(urlPath || '');
    setAdditionalQueries(urlQueries || '');

    if (urlDebugFormIsOpen) {
      setDebugFormIsOpen(urlDebugFormIsOpen);
    }
  }, [
    exactPathMapping,
    exampleQueries.length,
    serviceType,
    location.search,
    match.params,
    method,
    transport,
    useRequestBody,
    keepDebugResponse,
    docServiceRoute,
    setDebugFormIsOpen,
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

  const onExport = useCallback(() => {
    try {
      if (useRequestBody) {
        validateJsonObject(requestBody, 'request body');
      }

      if (additionalHeaders) {
        validateJsonObject(additionalHeaders, 'headers');
      }

      // window.location.origin may have compatibility issue
      // https://developer.mozilla.org/en-US/docs/Web/API/Window/location#Browser_compatibility
      const host =
        `${window.location.protocol}//${window.location.hostname}` +
        `${window.location.port ? `:${window.location.port}` : ''}`;

      const httpMethod = method.httpMethod;
      let mappedPath;
      let endpoint;

      if (
        serviceType === ServiceType.HTTP ||
        serviceType === ServiceType.GRAPHQL
      ) {
        const queries =
          additionalQueries.length > 0 ? `?${additionalQueries}` : '';

        if (exactPathMapping) {
          endpoint = transport.getDebugMimeTypeEndpoint(method);
          mappedPath =
            endpoint.pathMapping.substring('exact:'.length) + queries;
        } else {
          endpoint = transport.getDebugMimeTypeEndpoint(method, additionalPath);
          mappedPath = additionalPath + queries;
        }
      } else if (additionalPath.length > 0) {
        endpoint = transport.getDebugMimeTypeEndpoint(method, additionalPath);
        mappedPath = additionalPath;
      } else {
        endpoint = transport.getDebugMimeTypeEndpoint(method);
        mappedPath = endpoint.pathMapping;
      }

      const uri = `'${escapeSingleQuote(
        host + parseServerRootPath(docServiceRoute) + mappedPath,
      )}'`;

      const body = transport.getCurlBody(
        endpoint,
        method,
        escapeSingleQuote(requestBody),
      );

      const headers = new Headers();
      headers.set('content-type', transport.getDebugMimeType());
      if (process.env.WEBPACK_DEV === 'true') {
        headers.set(docServiceDebug, 'true');
      }
      if (serviceType === ServiceType.GRAPHQL) {
        headers.set('accept', 'application/json');
      }
      if (additionalHeaders) {
        const entries = Object.entries(JSON.parse(additionalHeaders));
        entries.forEach(([key, value]) => {
          headers.set(key, String(value));
        });
      }

      const headerOptions: string[] = [];
      headers.forEach((value, key) => {
        headerOptions.push(`-H '${key}: ${value}'`);
      });

      const curlCommand =
        `curl -X${httpMethod} ${headerOptions.join(' ')} ${uri}` +
        `${useRequestBody ? ` -d '${body}'` : ''}`;

      copyTextToClipboard(curlCommand);
      showSnackbar('The curl command has been copied to the clipboard.');
    } catch (e) {
      if (e instanceof Object) {
        setResponseData({
          body: e.toString?.() ?? '<unknown>',
          headers: [],
          status: undefined,
          executionTime: 0,
          size: 0,
          timestamp: new Date().toLocaleString(),
        });
      } else {
        setResponseData(null);
      }
    }
  }, [
    useRequestBody,
    additionalHeaders,
    method,
    transport,
    requestBody,
    serviceType,
    showSnackbar,
    additionalQueries,
    exactPathMapping,
    additionalPath,
    docServiceRoute,
  ]);

  const onCopy = useCallback(() => {
    const response = responseData?.body ?? '';
    if (response.length > 0) {
      copyTextToClipboard(response);
      showSnackbar('The response has been copied to the clipboard.');
    }
  }, [responseData, showSnackbar]);

  const onClear = useCallback(() => {
    setResponseData(null);
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
      if (serviceType === ServiceType.HTTP) {
        queries = params.get('queries') || '';
        if (!exactPathMapping) {
          executedEndpointPath = params.get('endpoint_path') || undefined;
        }
      } else {
        executedEndpointPath = params.get('endpoint_path') || undefined;
      }

      const headersText = params.get('headers');
      const headers = headersText ? JSON.parse(headersText) : {};

      try {
        const debugResponseData = await transport.send(
          method,
          headers,
          parseServerRootPath(docServiceRoute),
          executedRequestBody,
          executedEndpointPath,
          queries,
        );
        setResponseData(debugResponseData);
        setResponseCache((prev) => ({
          ...prev,
          [currentApiId]: debugResponseData,
        }));
      } catch (e) {
        setResponseData(null);
      }
    },
    [
      useRequestBody,
      serviceType,
      exactPathMapping,
      method,
      transport,
      docServiceRoute,
      currentApiId,
    ],
  );

  const onSubmit = useCallback(async () => {
    const queries = additionalQueries;
    const headers = additionalHeaders;
    const params = new URLSearchParams(location.search);

    try {
      if (useRequestBody) {
        // Do not round-trip through JSON.parse to minify the text so as to not lose numeric precision.
        // See: https://github.com/line/armeria/issues/273

        // For some reason jsonMinify minifies {} as empty string, so work around it.
        params.set('request_body', jsonMinify(requestBody) || '{}');
      }

      if (serviceType === ServiceType.HTTP) {
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
        setResponseData({
          body: e.toString?.() ?? '<unknown>',
          headers: [],
          status: undefined,
          executionTime: 0,
          size: 0,
          timestamp: new Date().toLocaleString(),
        });
      } else {
        setResponseData(null);
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
    serviceType,
    requestBody,
    exactPathMapping,
    additionalPath,
    history,
    method,
    transport,
  ]);

  const supportedExamplePaths = useMemo(() => {
    if (
      serviceType === ServiceType.HTTP ||
      serviceType === ServiceType.GRAPHQL
    ) {
      return examplePaths;
    }
    return transport.listDebugMimeTypeEndpoint(method).map((endpoint) => {
      return {
        label: endpoint.pathMapping,
        value: endpoint.pathMapping,
      };
    });
  }, [serviceType, transport, method, examplePaths]);

  const responseBody = responseData?.body;
  const responseHeadersString =
    Array.isArray(responseData?.headers) && responseData.headers.length > 0
      ? responseData.headers.join('\n')
      : '';
  return (
    <>
      <Section>
        <div id={debugFormIsOpen ? '' : 'debug-form'}>
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
              <DebugInputs
                method={method}
                serviceType={serviceType}
                exampleHeaders={exampleHeaders}
                exampleQueries={exampleQueries}
                supportedExamplePaths={supportedExamplePaths}
                additionalPath={additionalPath}
                setAdditionalPath={setAdditionalPath}
                additionalQueries={additionalQueries}
                setAdditionalQueries={setAdditionalQueries}
                exactPathMapping={exactPathMapping}
                useRequestBody={useRequestBody}
                additionalHeaders={additionalHeaders}
                setAdditionalHeaders={setAdditionalHeaders}
                jsonSchemas={jsonSchemas}
                stickyHeaders={stickyHeaders}
                toggleStickyHeaders={toggleStickyHeaders}
                requestBody={requestBody}
                setRequestBody={setRequestBody}
              />
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
                  <Tooltip title="Copy response body">
                    <div>
                      <IconButton
                        onClick={onCopy}
                        disabled={!responseData?.body}
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
                        disabled={!responseData?.body}
                      >
                        <DeleteSweepIcon />
                      </IconButton>
                    </div>
                  </Tooltip>
                </Grid>
                <ResponseStatusBar responseMetaData={responseData} />
              </Grid>
              {responseBody && (
                <>
                  {responseHeadersString && (
                    <>
                      <Typography
                        variant="subtitle2"
                        style={{ marginTop: '1rem', fontWeight: 'bold' }}
                      >
                        Response Headers:
                      </Typography>
                      <SyntaxHighlighter
                        language="text"
                        style={githubGist}
                        wrapLines={false}
                      >
                        {responseHeadersString}
                      </SyntaxHighlighter>
                    </>
                  )}
                  <Typography
                    variant="subtitle2"
                    style={{ marginTop: '1rem', fontWeight: 'bold' }}
                  >
                    Response Body:
                  </Typography>
                  <SyntaxHighlighter
                    language="json"
                    style={githubGist}
                    wrapLines={false}
                  >
                    {responseBody}
                  </SyntaxHighlighter>
                </>
              )}
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
      {/* Debug modal */}
      <Dialog
        onClose={() => setDebugFormIsOpen(false)}
        open={debugFormIsOpen}
        fullWidth
        maxWidth="lg"
      >
        <DialogTitle id="customized-dialog-title">
          <Typography variant="h6" paragraph>
            Debug
          </Typography>
        </DialogTitle>
        <DialogContent dividers>
          <div id="debug-form">
            <Typography variant="body2" paragraph />
            <Grid container spacing={2}>
              <Grid item xs={12} sm={6}>
                <DebugInputs
                  method={method}
                  serviceType={serviceType}
                  exampleHeaders={exampleHeaders}
                  exampleQueries={exampleQueries}
                  supportedExamplePaths={supportedExamplePaths}
                  additionalPath={additionalPath}
                  setAdditionalPath={setAdditionalPath}
                  additionalQueries={additionalQueries}
                  setAdditionalQueries={setAdditionalQueries}
                  exactPathMapping={exactPathMapping}
                  useRequestBody={useRequestBody}
                  additionalHeaders={additionalHeaders}
                  setAdditionalHeaders={setAdditionalHeaders}
                  jsonSchemas={jsonSchemas}
                  stickyHeaders={stickyHeaders}
                  toggleStickyHeaders={toggleStickyHeaders}
                  requestBody={requestBody}
                  setRequestBody={setRequestBody}
                />
                <Typography variant="body2" paragraph />
              </Grid>
              <Grid item xs={12} sm={6} className={classes.responseGrid}>
                <Grid container spacing={1}>
                  <Grid item xs="auto">
                    <Tooltip title="Copy response">
                      <div>
                        <IconButton
                          onClick={onCopy}
                          disabled={!responseData?.body}
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
                          disabled={!responseData?.body}
                        >
                          <DeleteSweepIcon />
                        </IconButton>
                      </div>
                    </Tooltip>
                  </Grid>
                  <ResponseStatusBar responseMetaData={responseData} />
                </Grid>
                {responseBody && (
                  <>
                    {responseHeadersString && (
                      <>
                        <Typography
                          variant="subtitle1"
                          style={{ marginTop: '1rem' }}
                        >
                          Response Headers:
                        </Typography>
                        <SyntaxHighlighter
                          language="text"
                          style={githubGist}
                          wrapLines={false}
                        >
                          {responseHeadersString}
                        </SyntaxHighlighter>
                      </>
                    )}
                    <Typography
                      variant="subtitle1"
                      style={{ marginTop: '1rem' }}
                    >
                      Response Body:
                    </Typography>
                    <SyntaxHighlighter
                      language="json"
                      style={githubGist}
                      wrapLines={false}
                    >
                      {responseBody}
                    </SyntaxHighlighter>
                  </>
                )}
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
        </DialogContent>
        <DialogActions className={classes.actionDialog}>
          <div>
            <Button variant="contained" color="primary" onClick={onSubmit}>
              Submit
            </Button>
            <Button variant="text" color="secondary" onClick={onExport}>
              Copy as a curl command
            </Button>
          </div>
          <Button
            autoFocus
            onClick={() => setDebugFormIsOpen(false)}
            variant="contained"
            color="primary"
          >
            Close
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
};

export default DebugPage;
