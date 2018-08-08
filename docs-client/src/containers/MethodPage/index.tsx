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
import ContentCopyIcon from '@material-ui/icons/ContentCopy';
import DeleteSweepIcon from '@material-ui/icons/DeleteSweep';
import jsonMinify from 'jsonminify';
import React, { ChangeEvent } from 'react';
import { RouteComponentProps } from 'react-router-dom';
import SyntaxHighlighter from 'react-syntax-highlighter';
import githubGist from 'react-syntax-highlighter/styles/hljs/github-gist';

import jsonPrettify from '../../lib/json-prettify';
import { simpleName, Specification } from '../../lib/specification';
import { TRANSPORTS } from '../../lib/transports';

import Section from '../../components/Section';
import VariableList from '../../components/VariableList';

interface State {
  debugRequest: string;
  debugResponse: string;
  additionalHeadersOpen: boolean;
  additionalHeaders: string;
  stickyHeaders: boolean;
}

interface OwnProps {
  specification: Specification;
}

type Props = OwnProps &
  RouteComponentProps<{ serviceName: string; methodName: string }>;

export default class MethodPage extends React.PureComponent<Props, State> {
  public state: State = {
    debugRequest: '',
    debugResponse: '',
    additionalHeadersOpen: false,
    additionalHeaders: '',
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
            title="Parameters"
            variables={method.parameters}
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
                <TableRow key={`${endpoint.hostnamePattern}/${endpoint.path}`}>
                  <TableCell>{endpoint.hostnamePattern}</TableCell>
                  <TableCell>{endpoint.path}</TableCell>
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
                <TextField
                  multiline
                  fullWidth
                  rows={15}
                  value={this.state.debugRequest}
                  onChange={this.onDebugFormChange}
                  inputProps={{
                    className: 'code',
                  }}
                />
                <Typography variant="body1" paragraph />
                <Button color="secondary" onClick={this.onEditHttpHeadersClick}>
                  Edit HTTP headers
                </Button>
                <Typography variant="body1" paragraph />
                {this.state.additionalHeadersOpen && (
                  <>
                    <TextField
                      multiline
                      fullWidth
                      rows={8}
                      value={this.state.additionalHeaders}
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
                    <ContentCopyIcon />
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
      debugRequest: e.target.value,
    });
  };

  private onHeadersFormChange = (e: ChangeEvent<HTMLInputElement>) => {
    this.setState({
      additionalHeaders: e.target.value,
    });
  };

  private onStickyHeadersChange = () => {
    this.setState({
      stickyHeaders: !this.state.stickyHeaders,
    });
  };

  private onEditHttpHeadersClick = () => {
    this.setState({
      additionalHeadersOpen: !this.state.additionalHeadersOpen,
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
    const args = this.state.debugRequest;
    const headers = this.state.additionalHeaders;
    const params = new URLSearchParams(this.props.location.search);

    try {
      const parsedArgs = JSON.parse(args);
      if (typeof parsedArgs !== 'object') {
        this.setState({
          debugResponse: `Arguments must be a JSON object.\nYou entered: ${typeof parsedArgs}`,
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
    const minifiedArgs = jsonMinify(args) || '{}';
    params.set('args', minifiedArgs);

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

  private async executeRequest() {
    const params = new URLSearchParams(this.props.location.search);
    const argsText = params.get('args');
    if (!argsText) {
      return;
    }
    const headersText = params.get('http_headers');
    const headers = headersText ? JSON.parse(headersText) : {};

    const method = this.getMethod()!;
    const transport = TRANSPORTS.getDebugTransport(method)!;
    let debugResponse;
    try {
      debugResponse = await transport.send(method, argsText, headers);
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
    const urlDebugRequest = urlParams.has('args')
      ? jsonPrettify(urlParams.get('args')!)
      : undefined;

    const urlHeaders = urlParams.has('http_headers')
      ? jsonPrettify(urlParams.get('http_headers')!)
      : undefined;

    const stateHeaders = this.state.stickyHeaders
      ? this.state.additionalHeaders
      : undefined;

    const exampleHeaders =
      service.exampleHttpHeaders.length > 0
        ? JSON.stringify(service.exampleHttpHeaders[0], null, 2)
        : undefined;

    const hasHeaders = !!(urlHeaders || stateHeaders || exampleHeaders);
    this.setState({
      debugRequest: urlDebugRequest || method.exampleRequests[0] || '',
      debugResponse: '',
      additionalHeaders: urlHeaders || stateHeaders || exampleHeaders || '',
      additionalHeadersOpen: hasHeaders,
      stickyHeaders:
        urlParams.has('http_headers_sticky') || this.state.stickyHeaders,
    });
  }
}
