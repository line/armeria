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

import Button from '@material-ui/core/Button';
import Checkbox from '@material-ui/core/Checkbox';
import FormControlLabel from '@material-ui/core/FormControlLabel';
import Grid from '@material-ui/core/Grid';
import IconButton from '@material-ui/core/IconButton';
import List from '@material-ui/core/List';
import ListItem from '@material-ui/core/ListItem';
import ListItemText from '@material-ui/core/ListItemText';
import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import TextField from '@material-ui/core/TextField';
import Tooltip from '@material-ui/core/Tooltip';
import Typography from '@material-ui/core/Typography';
import DeleteSweepIcon from '@material-ui/icons/DeleteSweep';
import FileCopyIcon from '@material-ui/icons/FileCopy';
import jsonMinify from 'jsonminify';
import React, { ChangeEvent } from 'react';
import Dropdown, { Option } from 'react-dropdown';
import { RouteComponentProps } from 'react-router-dom';
import SyntaxHighlighter from 'react-syntax-highlighter';
import githubGist from 'react-syntax-highlighter/styles/hljs/github-gist';

import jsonPrettify from '../../lib/json-prettify';
import {
  endpointPathString,
  isExactPathMapping,
  simpleName,
  Specification,
} from '../../lib/specification';
import { TRANSPORTS } from '../../lib/transports';
import { ANNOTATED_HTTP_MIME_TYPE } from '../../lib/transports/annotated-http';

import Section from '../../components/Section';
import VariableList from '../../components/VariableList';

interface State {
  requestBodyOpen: boolean;
  requestBody: string;
  debugResponse: string;
  additionalQueriesOpen: boolean;
  additionalQueries: string;
  endpointPathOpen: boolean;
  endpointPath: string;
  originalPath: string;
  additionalHeadersOpen: boolean;
  additionalHeaders: string;
  exampleHeaders: Option[];
  stickyHeaders: boolean;
}

interface OwnProps {
  specification: Specification;
}

type Props = OwnProps &
  RouteComponentProps<{ serviceName: string; methodName: string }>;

export default class MethodPage extends React.PureComponent<Props, State> {
  public state: State = {
    requestBodyOpen: true,
    requestBody: '',
    debugResponse: '',
    additionalQueriesOpen: false,
    additionalQueries: '',
    endpointPathOpen: false,
    endpointPath: '',
    originalPath: '',
    additionalHeadersOpen: false,
    additionalHeaders: '',
    exampleHeaders: [],
    stickyHeaders: false,
  };

  public componentDidMount() {
    this.initializeState();
    this.executeRequest();
  }

  public componentDidUpdate(prevProps: Props) {
    if (this.props.match.params !== prevProps.match.params) {
      this.initializeState();
    }
    if (this.props.location.search !== prevProps.location.search) {
      this.executeRequest();
    }
  }

  public render() {
    const { specification } = this.props;

    const service = this.getService();
    if (!service) {
      return <>Not found.</>;
    }
    const method = this.getMethod();
    if (!method) {
      return <>Not found.</>;
    }

    const debugTransport = TRANSPORTS.getDebugTransport(method);
    const isAnnotatedHttpService =
      debugTransport !== undefined &&
      debugTransport.supportsMimeType(ANNOTATED_HTTP_MIME_TYPE);

    const jsonPlaceHolder = jsonPrettify('{"foo":"bar"}');
    let endpointPathPlaceHolder;
    let queryPlaceHolder;

    if (isAnnotatedHttpService) {
      endpointPathPlaceHolder = '/foo/bar';
      queryPlaceHolder = 'foo=bar&baz=qux';
    } else {
      endpointPathPlaceHolder = '';
      queryPlaceHolder = '';
    }

    return (
      <>
        <Typography variant="headline" paragraph>
          <code>{`${simpleName(service.name)}.${method.name}()`}</code>
        </Typography>
        <Typography variant="body1" paragraph>
          {method.docString}
        </Typography>
        <Section>
          <VariableList
            key={method.name}
            title="Parameters"
            variables={method.parameters}
            hasLocation={isAnnotatedHttpService}
            specification={specification}
          />
        </Section>
        <Section>
          <Typography variant="title">Return Type</Typography>
          <Table>
            <TableBody>
              <TableRow>
                <TableCell>
                  <code>
                    {specification.getTypeSignatureHtml(
                      method.returnTypeSignature,
                    )}
                  </code>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </Section>
        <Section>
          <Typography variant="title">Exceptions</Typography>
          <Table>
            <TableBody>
              {method.exceptionTypeSignatures.length > 0 ? (
                method.exceptionTypeSignatures.map((exception) => (
                  <TableRow key={exception}>
                    <TableCell>
                      <code>
                        {specification.getTypeSignatureHtml(exception)}
                      </code>
                    </TableCell>
                  </TableRow>
                ))
              ) : (
                <TableRow>
                  <TableCell>There are no exceptions</TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </Section>
        <Section>
          <Typography variant="title">Endpoints</Typography>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Hostname</TableCell>
                <TableCell>Path</TableCell>
                <TableCell>MIME types</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {method.endpoints.map((endpoint) => (
                <TableRow
                  key={`${endpoint.hostnamePattern}/${endpoint.pathMapping}`}
                >
                  <TableCell>{endpoint.hostnamePattern}</TableCell>
                  <TableCell>{endpointPathString(endpoint)}</TableCell>
                  <TableCell>
                    <List dense>
                      {endpoint.availableMimeTypes.map((mimeType) => (
                        <ListItem key={mimeType}>
                          <ListItemText
                            primary={mimeType}
                            primaryTypographyProps={{
                              style: {
                                fontWeight:
                                  mimeType === endpoint.defaultMimeType
                                    ? 'bold'
                                    : 'normal',
                              },
                            }}
                          />
                        </ListItem>
                      ))}
                    </List>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Section>
        {TRANSPORTS.getDebugTransport(method) && (
          <Section>
            <Typography variant="body1" paragraph />
            <Grid container spacing={16}>
              <Grid item xs={12} sm={6}>
                <Typography variant="title" paragraph>
                  Debug
                </Typography>
                {isAnnotatedHttpService &&
                  ((isExactPathMapping(method) && (
                    <>
                      <Typography variant="body1" paragraph />
                      <Button
                        color="secondary"
                        onClick={this.onEditHttpQueriesClick}
                      >
                        HTTP query string
                      </Button>
                      <Typography variant="body1" paragraph />
                      {this.state.additionalQueriesOpen && (
                        <>
                          <TextField
                            multiline
                            fullWidth
                            rows={1}
                            value={this.state.additionalQueries}
                            placeholder={queryPlaceHolder}
                            onChange={this.onQueriesFormChange}
                            inputProps={{
                              className: 'code',
                            }}
                          />
                        </>
                      )}
                    </>
                  )) ||
                    (!isExactPathMapping(method) && (
                      <>
                        <Typography variant="body1" paragraph />
                        <Button
                          color="secondary"
                          onClick={this.onEditEndpointPathClick}
                        >
                          Endpoint path
                        </Button>
                        <Typography variant="body1" paragraph />
                        {this.state.endpointPathOpen && (
                          <>
                            <TextField
                              multiline
                              fullWidth
                              rows={1}
                              value={this.state.endpointPath}
                              placeholder={endpointPathPlaceHolder}
                              onChange={this.onEndpointPathChange.bind(
                                this,
                                method.endpoints[0].pathMapping,
                              )}
                              inputProps={{
                                className: 'code',
                              }}
                            />
                          </>
                        )}
                      </>
                    )))}
                <Typography variant="body1" paragraph />
                <Button color="secondary" onClick={this.onEditHttpHeadersClick}>
                  HTTP headers
                </Button>
                <Typography variant="body1" paragraph />
                {this.state.additionalHeadersOpen && (
                  <>
                    <>
                      {this.state.exampleHeaders.length > 0 && (
                        <Dropdown
                          placeholder="Select an example headers..."
                          options={this.state.exampleHeaders}
                          onChange={this.onSelectedHeadersChange}
                        />
                      )}
                    </>
                    <TextField
                      multiline
                      fullWidth
                      rows={8}
                      value={this.state.additionalHeaders}
                      placeholder={jsonPlaceHolder}
                      onChange={this.onHeadersFormChange}
                      inputProps={{
                        className: 'code',
                      }}
                    />
                    <Typography variant="body1" paragraph />
                    <FormControlLabel
                      control={
                        <Checkbox
                          checked={this.state.stickyHeaders}
                          onChange={this.onStickyHeadersChange}
                        />
                      }
                      label="Use these HTTP headers for all functions."
                    />
                    <Typography variant="body1" paragraph />
                  </>
                )}
                <Button color="secondary" onClick={this.onEditRequestBodyClick}>
                  Request body
                </Button>
                <Typography variant="body1" paragraph />
                {this.state.requestBodyOpen && (
                  <TextField
                    multiline
                    fullWidth
                    rows={15}
                    value={this.state.requestBody}
                    placeholder={jsonPlaceHolder}
                    onChange={this.onDebugFormChange}
                    inputProps={{
                      className: 'code',
                    }}
                  />
                )}
                <Button
                  variant="contained"
                  color="primary"
                  onClick={this.onSubmit}
                >
                  Submit
                </Button>
              </Grid>
              <Grid item xs={12} sm={6}>
                <Tooltip title="Copy response">
                  <IconButton onClick={this.onCopy}>
                    <FileCopyIcon />
                  </IconButton>
                </Tooltip>
                <Tooltip title="Clear response">
                  <IconButton onClick={this.onClear}>
                    <DeleteSweepIcon />
                  </IconButton>
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
          </Section>
        )}
      </>
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

  private onEndpointPathChange = (
    originalPath: string,
    e: ChangeEvent<HTMLInputElement>,
  ) => {
    this.setState({
      originalPath,
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
    const textArea = document.createElement('textarea');
    textArea.style.opacity = '0.0';
    textArea.value = response;
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    document.execCommand('copy');
    document.body.removeChild(textArea);
  };

  private onClear = () => {
    this.setState({
      debugResponse: '',
    });
  };

  private onSubmit = () => {
    const requestBody = this.state.requestBody;
    const endpointPath = this.state.endpointPath;
    const queries = this.state.additionalQueries;
    const headers = this.state.additionalHeaders;
    const params = new URLSearchParams(this.props.location.search);

    try {
      const parsedArgs = JSON.parse(requestBody);
      if (typeof parsedArgs !== 'object') {
        this.setState({
          debugResponse: `The request body must be a JSON object.\nYou entered: ${typeof parsedArgs}`,
        });
      }
    } catch (e) {
      this.setState({
        debugResponse: `Failed to parse a JSON object in the arguments field:\n${e}`,
      });
    }
    // Do not round-trip through JSON.parse to minify the text so as to not lose numeric precision.
    // See: https://github.com/line/armeria/issues/273

    // For some reason jsonMinify minifies {} as empty string, so work around it.
    const minifiedRequestBody = jsonMinify(requestBody) || '{}';
    params.set('request_body', minifiedRequestBody);

    if (endpointPath) {
      try {
        this.validateEndpointPath(endpointPath);
      } catch (e) {
        this.setState({ debugResponse: e.toString() });
        return;
      }
      params.set('endpoint_path', endpointPath);
    }
    if (queries) {
      params.set('queries', queries);
    }

    if (headers) {
      try {
        const parsedHeaders = JSON.parse(headers);
        if (typeof params !== 'object') {
          this.setState({
            debugResponse: `HTTP headers must be a JSON object.\nYou entered: ${typeof parsedHeaders}`,
          });
        }
      } catch (e) {
        this.setState({
          debugResponse: `Failed to parse a JSON object in the HTTP headers field:\n${e}`,
        });
      }
      let minifiedHeaders = jsonMinify(headers);
      if (minifiedHeaders === '{}') {
        minifiedHeaders = '';
      }
      if (minifiedHeaders.length > 0) {
        params.set('http_headers', minifiedHeaders);
      }
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
    } else {
      this.executeRequest();
    }
  };

  private validateEndpointPath(endpointPath: string) {
    const method = this.getMethod()!;
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

  private async executeRequest() {
    const params = new URLSearchParams(this.props.location.search);
    const requestBody = params.get('request_body');
    if (!requestBody) {
      return;
    }

    const endpointPathText = params.get('endpoint_path');
    const endpointPath = endpointPathText ? endpointPathText : undefined;

    const queriesText = params.get('queries');
    const queries = queriesText ? queriesText : '';

    const headersText = params.get('http_headers');
    const headers = headersText ? JSON.parse(headersText) : {};

    const method = this.getMethod()!;
    const transport = TRANSPORTS.getDebugTransport(method)!;
    let debugResponse;
    try {
      debugResponse = await transport.send(
        method,
        requestBody,
        headers,
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

  private getService() {
    return this.props.specification.getServiceByName(
      this.props.match.params.serviceName,
    );
  }

  private getMethod() {
    const service = this.getService()!;
    return service.methods.find(
      (m) => m.name === this.props.match.params.methodName,
    );
  }

  private addExampleHeadersIfExists(
    dst: Option[],
    src: { [name: string]: string }[],
  ) {
    if (src.length > 0) {
      for (const headers of src) {
        dst.push({
          value: JSON.stringify(headers, null, 2),
          label: this.removeBrackets(JSON.stringify(headers).trim()),
        });
      }
    }
  }

  private removeBrackets(headers: string): string {
    const length = headers.length;
    return headers.substring(1, length - 1).trim();
  }

  private initializeState() {
    const service = this.getService();
    if (!service) {
      return;
    }
    const method = this.getMethod();
    if (!method) {
      return;
    }

    const urlParams = new URLSearchParams(this.props.location.search);
    const urlRequestBody = urlParams.has('request_body')
      ? jsonPrettify(urlParams.get('request_body')!)
      : undefined;

    const urlHeaders = urlParams.has('http_headers')
      ? jsonPrettify(urlParams.get('http_headers')!)
      : undefined;

    const urlEndpointPath = urlParams.has('endpoint_path')
      ? urlParams.get('endpoint_path')!
      : '';

    const urlQueries = urlParams.has('queries')
      ? urlParams.get('queries')!
      : '';

    const stateHeaders = this.state.stickyHeaders
      ? this.state.additionalHeaders
      : undefined;

    const exampleHeaders: Option[] = [];
    this.addExampleHeadersIfExists(exampleHeaders, method.exampleHttpHeaders);
    this.addExampleHeadersIfExists(exampleHeaders, service.exampleHttpHeaders);
    this.addExampleHeadersIfExists(
      exampleHeaders,
      this.props.specification.getExampleHttpHeaders(),
    );

    const headersOpen = !!(urlHeaders || stateHeaders);
    this.setState({
      exampleHeaders,
      requestBody: urlRequestBody || method.exampleRequests[0] || '',
      requestBodyOpen: !!(urlRequestBody || method.exampleRequests[0]),
      debugResponse: '',
      additionalQueries: urlQueries,
      additionalQueriesOpen: !!urlQueries,
      endpointPath: urlEndpointPath,
      endpointPathOpen: !!urlEndpointPath,
      originalPath: '',
      additionalHeaders:
        urlHeaders ||
        stateHeaders ||
        (exampleHeaders.length === 1 ? exampleHeaders[0].value || '' : ''),
      additionalHeadersOpen: headersOpen,
      stickyHeaders:
        urlParams.has('http_headers_sticky') || this.state.stickyHeaders,
    });
  }
}
