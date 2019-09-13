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
import React, { ChangeEvent } from 'react';
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
  isAnnotatedHttpService: boolean;
  exampleHeaders: Option[];
  exactPathMapping: boolean;
  useRequestBody: boolean;
}

interface State {
  requestBodyOpen: boolean;
  requestBody: string;
  debugResponse: string;
  additionalQueriesOpen: boolean;
  additionalQueries: string;
  endpointPathOpen: boolean;
  endpointPath: string;
  additionalHeadersOpen: boolean;
  additionalHeaders: string;
  stickyHeaders: boolean;
  snackbarOpen: boolean;
  snackbarMessage: string;
}

type Props = OwnProps & RouteComponentProps;

class DebugPage extends React.PureComponent<Props, State> {
  private static validateJsonObject(jsonObject: string, description: string) {
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
  }

  private static copyTextToClipboard(text: string) {
    const textArea = document.createElement('textarea');
    textArea.style.opacity = '0.0';
    textArea.value = text;
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    document.execCommand('copy');
    document.body.removeChild(textArea);
  }

  public state = {
    requestBodyOpen: true,
    requestBody: '',
    debugResponse: '',
    additionalQueriesOpen: false,
    additionalQueries: '',
    endpointPathOpen: false,
    endpointPath: '',
    additionalHeadersOpen: false,
    additionalHeaders: '',
    stickyHeaders: false,
    snackbarOpen: false,
    snackbarMessage: '',
  };

  public componentDidMount() {
    this.initializeState();
  }

  public componentDidUpdate(prevProps: Props) {
    if (this.props.match.params !== prevProps.match.params) {
      this.initializeState();
    }
  }

  public render() {
    return (
      <Section>
        <div id="debug-form">
          <Typography variant="body2" paragraph />
          <Grid container spacing={2}>
            <Grid item xs={12} sm={6}>
              <Typography variant="h6" paragraph>
                Debug
              </Typography>
              {this.props.isAnnotatedHttpService &&
                (this.props.exactPathMapping ? (
                  <>
                    <HttpQueryString
                      additionalQueriesOpen={this.state.additionalQueriesOpen}
                      additionalQueries={this.state.additionalQueries}
                      onEditHttpQueriesClick={this.onEditHttpQueriesClick}
                      onQueriesFormChange={this.onQueriesFormChange}
                    />
                  </>
                ) : (
                  <EndpointPath
                    endpointPathOpen={this.state.endpointPathOpen}
                    endpointPath={this.state.endpointPath}
                    onEditEndpointPathClick={this.onEditEndpointPathClick}
                    onEndpointPathChange={this.onEndpointPathChange}
                  />
                ))}
              <HttpHeaders
                exampleHeaders={this.props.exampleHeaders}
                additionalHeadersOpen={this.state.additionalHeadersOpen}
                additionalHeaders={this.state.additionalHeaders}
                stickyHeaders={this.state.stickyHeaders}
                onEditHttpHeadersClick={this.onEditHttpHeadersClick}
                onSelectedHeadersChange={this.onSelectedHeadersChange}
                onHeadersFormChange={this.onHeadersFormChange}
                onStickyHeadersChange={this.onStickyHeadersChange}
              />
              {this.props.useRequestBody && (
                <>
                  <RequestBody
                    requestBodyOpen={this.state.requestBodyOpen}
                    requestBody={this.state.requestBody}
                    onEditRequestBodyClick={this.onEditRequestBodyClick}
                    onDebugFormChange={this.onDebugFormChange}
                  />
                </>
              )}
              <Typography variant="body2" paragraph />
              <Button
                variant="contained"
                color="primary"
                onClick={this.onSubmit}
              >
                Submit
              </Button>
              <Button variant="text" color="secondary" onClick={this.onExport}>
                Copy as a curl command
              </Button>
            </Grid>
            <Grid item xs={12} sm={6}>
              <Tooltip title="Copy response">
                <div>
                  <IconButton
                    onClick={this.onCopy}
                    disabled={this.state.debugResponse.length === 0}
                  >
                    <FileCopyIcon />
                  </IconButton>
                </div>
              </Tooltip>
              <Tooltip title="Clear response">
                <div>
                  <IconButton
                    onClick={this.onClear}
                    disabled={this.state.debugResponse.length === 0}
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
                {this.state.debugResponse}
              </SyntaxHighlighter>
            </Grid>
          </Grid>
          <Snackbar
            open={this.state.snackbarOpen}
            message={this.state.snackbarMessage}
            autoHideDuration={3000}
            onClose={this.onSnackbarDismiss}
            action={
              <IconButton color="inherit" onClick={this.onSnackbarDismiss}>
                <CloseIcon />
              </IconButton>
            }
          />
        </div>
      </Section>
    );
  }

  private onDebugFormChange = (e: ChangeEvent<HTMLInputElement>) => {
    this.setState({
      requestBody: e.target.value,
    });
  };

  private onHeadersFormChange = (e: ChangeEvent<HTMLInputElement>) => {
    this.setState({
      additionalHeaders: e.target.value,
    });
  };

  private onSelectedHeadersChange = (selectedHeaders: Option) => {
    this.setState({
      additionalHeaders: selectedHeaders.value,
    });
  };

  private onQueriesFormChange = (e: ChangeEvent<HTMLInputElement>) => {
    this.setState({
      additionalQueries: e.target.value,
    });
  };

  private onEndpointPathChange = (e: ChangeEvent<HTMLInputElement>) => {
    this.setState({
      endpointPath: e.target.value,
    });
  };

  private onStickyHeadersChange = () => {
    this.setState({
      stickyHeaders: !this.state.stickyHeaders,
    });
  };

  private onEditHttpQueriesClick = () => {
    this.setState({
      additionalQueriesOpen: !this.state.additionalQueriesOpen,
    });
  };

  private onEditEndpointPathClick = () => {
    this.setState({
      endpointPathOpen: !this.state.endpointPathOpen,
    });
  };

  private onEditHttpHeadersClick = () => {
    this.setState({
      additionalHeadersOpen: !this.state.additionalHeadersOpen,
    });
  };

  private onEditRequestBodyClick = () => {
    this.setState({
      requestBodyOpen: !this.state.requestBodyOpen,
    });
  };

  private onCopy = () => {
    const response = this.state.debugResponse;
    if (response.length > 0) {
      DebugPage.copyTextToClipboard(response);
      this.showSnackbar('The response has been copied to the clipboard.');
    }
  };

  private onExport = () => {
    const escapeSingleQuote = (text: string) => text.replace(/'/g, `'\\''`);

    const additionalHeaders = this.state.additionalHeaders;
    const requestBody = this.state.requestBody;

    try {
      if (this.props.useRequestBody) {
        DebugPage.validateJsonObject(requestBody, 'request body');
      }

      if (additionalHeaders) {
        DebugPage.validateJsonObject(additionalHeaders, 'headers');
      }

      const headers =
        (additionalHeaders && JSON.parse(additionalHeaders)) || {};

      // window.location.origin may have compatibility issue
      // https://developer.mozilla.org/en-US/docs/Web/API/Window/location#Browser_compatibility
      const host =
        `${window.location.protocol}//${window.location.hostname}` +
        `${window.location.port ? `:${window.location.port}` : ''}`;

      const method = this.props.method;
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

      if (this.props.isAnnotatedHttpService) {
        if (this.props.exactPathMapping) {
          const queries = this.state.additionalQueries;
          uri =
            `'${host}${escapeSingleQuote(path.substring('exact:'.length))}` +
            `${queries.length > 0 ? `?${escapeSingleQuote(queries)}` : ''}'`;
        } else {
          const endpointPath = this.state.endpointPath;
          this.validateEndpointPath(endpointPath);
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
        `${this.props.useRequestBody ? ` -d '${body}'` : ''}`;

      DebugPage.copyTextToClipboard(curlCommand);
      this.showSnackbar('The curl command has been copied to the clipboard.');
    } catch (e) {
      this.setState({
        debugResponse: e.toString(),
      });
    }
  };

  private onClear = () => {
    this.setState({
      debugResponse: '',
    });
  };

  private onSubmit = () => {
    this.setState({
      debugResponse: '',
    });

    const requestBody = this.state.requestBody;
    const endpointPath = this.state.endpointPath;
    const queries = this.state.additionalQueries;
    const headers = this.state.additionalHeaders;
    const params = new URLSearchParams(this.props.location.search);

    try {
      if (this.props.useRequestBody) {
        DebugPage.validateJsonObject(requestBody, 'request body');

        // Do not round-trip through JSON.parse to minify the text so as to not lose numeric precision.
        // See: https://github.com/line/armeria/issues/273

        // For some reason jsonMinify minifies {} as empty string, so work around it.
        const minifiedRequestBody = jsonMinify(requestBody) || '{}';
        params.set('request_body', minifiedRequestBody);
      }

      if (this.props.isAnnotatedHttpService) {
        if (this.props.exactPathMapping) {
          if (queries) {
            params.set('queries', queries);
          }
        } else {
          this.validateEndpointPath(endpointPath);
          params.set('endpoint_path', endpointPath);
        }
      }

      if (headers) {
        DebugPage.validateJsonObject(headers, 'HTTP headers');
        let minifiedHeaders = jsonMinify(headers);
        if (minifiedHeaders === '{}') {
          minifiedHeaders = '';
        }
        if (minifiedHeaders.length > 0) {
          params.set('http_headers', minifiedHeaders);
        }
      }
    } catch (e) {
      this.setState({
        debugResponse: e.toString(),
      });
      return;
    }

    if (this.state.stickyHeaders) {
      params.set('http_headers_sticky', 'true');
    } else {
      params.delete('http_headers_sticky');
    }

    const serializedParams = `?${params.toString()}`;
    if (serializedParams !== this.props.location.search) {
      this.props.history.push(
        `${this.props.location.pathname}${serializedParams}`,
      );
    }
    this.executeRequest(params);
  };

  private showSnackbar = (text: string) => {
    this.setState({
      snackbarOpen: true,
      snackbarMessage: text,
    });
  };

  private onSnackbarDismiss = () => {
    this.setState({
      snackbarOpen: false,
    });
  };

  private validateEndpointPath(endpointPath: string) {
    if (!endpointPath) {
      throw new Error('You must specify the endpoint path.');
    }
    const method = this.props.method;
    const endpoint = method.endpoints[0];
    const regexPathPrefix = endpoint.regexPathPrefix;
    const originalPath = endpoint.pathMapping;

    if (originalPath.startsWith('prefix:')) {
      // Prefix path mapping.
      const prefix = originalPath.substring('prefix:'.length);
      if (!endpointPath.startsWith(prefix)) {
        throw new Error(
          `The path: '${endpointPath}' should start with the prefix: ${prefix}`,
        );
      }
    }

    if (originalPath.startsWith('regex:')) {
      let regexPart;
      if (regexPathPrefix) {
        // Prefix adding path mapping.
        const prefix = regexPathPrefix.substring('prefix:'.length);
        if (!endpointPath.startsWith(prefix)) {
          throw new Error(
            `The path: '${endpointPath}' should start with the prefix: ${prefix}`,
          );
        }

        // Remove the prefix from the endpointPath so that we can test the regex.
        regexPart = endpointPath.substring(prefix.length - 1);
      } else {
        regexPart = endpointPath;
      }
      const regExp = new RegExp(originalPath.substring('regex:'.length));
      if (!regExp.test(regexPart)) {
        const expectedPath = regexPathPrefix
          ? `${regexPathPrefix} ${originalPath}`
          : originalPath;
        throw new Error(
          `Endpoint path: ${endpointPath} (expected: ${expectedPath})`,
        );
      }
    }
  }

  private initializeState() {
    const urlParams = new URLSearchParams(this.props.location.search);

    let urlRequestBody;
    if (this.props.useRequestBody) {
      if (urlParams.has('request_body')) {
        urlRequestBody = jsonPrettify(urlParams.get('request_body')!);
        this.scrollToDebugForm();
      }
    }

    const urlHeaders = urlParams.has('http_headers')
      ? jsonPrettify(urlParams.get('http_headers')!)
      : undefined;

    let urlQueries = '';
    let urlEndpointPath = '';
    if (this.props.isAnnotatedHttpService) {
      if (this.props.exactPathMapping) {
        if (urlParams.has('queries')) {
          urlQueries = urlParams.get('queries')!;
        }
      } else if (urlParams.has('endpoint_path')) {
        urlEndpointPath = urlParams.get('endpoint_path')!;
      }
    }

    const stateHeaders = this.state.stickyHeaders
      ? this.state.additionalHeaders
      : undefined;

    const headersOpen = !!(urlHeaders || stateHeaders);
    this.setState({
      requestBody: urlRequestBody || this.props.method.exampleRequests[0] || '',
      requestBodyOpen: this.state.requestBodyOpen,
      debugResponse: '',
      additionalQueries: urlQueries,
      additionalQueriesOpen: !!urlQueries,
      endpointPath: urlEndpointPath,
      endpointPathOpen: !!urlEndpointPath,
      additionalHeaders: urlHeaders || stateHeaders || '',
      additionalHeadersOpen: headersOpen,
      stickyHeaders:
        urlParams.has('http_headers_sticky') || this.state.stickyHeaders,
      snackbarOpen: false,
      snackbarMessage: '',
    });
  }

  private async executeRequest(params: URLSearchParams) {
    let requestBody;
    if (this.props.useRequestBody) {
      requestBody = params.get('request_body');
      if (!requestBody) {
        return;
      }
    }

    let queries;
    let endpointPath;
    if (this.props.isAnnotatedHttpService) {
      if (this.props.exactPathMapping) {
        const queriesText = params.get('queries');
        queries = queriesText ? queriesText : '';
      } else {
        const endpointPathText = params.get('endpoint_path');
        endpointPath = endpointPathText ? endpointPathText : undefined;
      }
    }

    const headersText = params.get('http_headers');
    const headers = headersText ? JSON.parse(headersText) : {};

    const method = this.props.method;
    const transport = TRANSPORTS.getDebugTransport(method)!;
    let debugResponse;
    try {
      debugResponse = await transport.send(
        method,
        headers,
        requestBody,
        endpointPath,
        queries,
      );
    } catch (e) {
      debugResponse = e.toString();
    }
    this.setState({
      debugResponse,
    });
  }

  private scrollToDebugForm() {
    const scrollNode = document.getElementById('debug-form');
    if (scrollNode) {
      scrollNode.scrollIntoView({ behavior: 'smooth' });
    }
  }
}

export default DebugPage;
