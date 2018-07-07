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
import Grid from '@material-ui/core/Grid';
import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import TextField from '@material-ui/core/TextField';
import Typography from '@material-ui/core/Typography';
import React, { ChangeEvent } from 'react';
import { RouteComponentProps } from 'react-router-dom';

import { simpleName, Specification } from '../../lib/specification';
import { TRANSPORTS } from '../../lib/transports';

interface State {
  debugRequest: string;
  debugResponse: string;
  additionalHeadersOpen: boolean;
  additionalHeaders: string;
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
        <Typography variant="title">Parameters</Typography>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Required</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>Description</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {method.parameters.map((param) => (
              <TableRow key={param.name}>
                <TableCell>
                  <code>{param.name}</code>
                </TableCell>
                <TableCell>{param.requirement}</TableCell>
                <TableCell>
                  <code>
                    {specification.getTypeSignatureHtml(param.typeSignature)}
                  </code>
                </TableCell>
                <TableCell>{param.docString}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Typography variant="body1" paragraph />
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
        {method.exceptionTypeSignatures.length > 0 && (
          <>
            <Typography variant="body1" paragraph />
            <Typography variant="title">Exceptions</Typography>
            <Table>
              <TableBody>
                {method.exceptionTypeSignatures.map((exception) => (
                  <TableRow key={exception}>
                    <TableCell>
                      <code>
                        {specification.getTypeSignatureHtml(exception)}
                      </code>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
        <Typography variant="body1" paragraph />
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
                  <ul>
                    {endpoint.availableMimeTypes.map((mimeType) => (
                      <li
                        style={{
                          fontWeight:
                            mimeType === endpoint.defaultMimeType
                              ? 'bold'
                              : 'normal',
                        }}
                      >
                        {mimeType}
                      </li>
                    ))}
                  </ul>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {TRANSPORTS.getDebugTransport(method) && (
          <>
            <Typography variant="body1" paragraph />
            <Typography variant="title" paragraph>
              Debug
            </Typography>
            <Grid container spacing={16}>
              <Grid item xs={12} sm={6}>
                <TextField
                  multiline
                  fullWidth
                  rows={15}
                  value={this.state.debugRequest}
                  onChange={this.onDebugFormChange}
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
                <TextField
                  multiline
                  fullWidth
                  disabled
                  rows={15}
                  value={this.state.debugResponse}
                />
              </Grid>
            </Grid>
          </>
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

  private onEditHttpHeadersClick = () => {
    this.setState({
      additionalHeadersOpen: !this.state.additionalHeadersOpen,
    });
  };

  private onSubmit = async () => {
    const args = this.state.debugRequest;
    const headers = this.state.additionalHeaders;
    const params = new URLSearchParams(this.props.location.search);
    params.set('args', JSON.stringify(JSON.parse(args)));
    if (headers) {
      params.set('http_headers', JSON.stringify(JSON.parse(headers)));
    }
    this.props.history.push(
      `${this.props.location.pathname}?${params.toString()}`,
    );
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
      ? JSON.stringify(JSON.parse(urlParams.get('args')!), null, 2)
      : null;
    const urlHeaders = urlParams.has('http_headers')
      ? JSON.stringify(JSON.parse(urlParams.get('http_headers')!), null, 2)
      : null;

    const hasHeaders = !!urlHeaders || service.exampleHttpHeaders.length > 0;
    this.setState({
      debugRequest: urlDebugRequest || method.exampleRequests[0] || '',
      additionalHeaders:
        urlHeaders ||
        (hasHeaders
          ? JSON.stringify(service.exampleHttpHeaders[0], null, 2)
          : ''),
      additionalHeadersOpen: hasHeaders,
    });
  }
}
