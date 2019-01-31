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

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import Typography from '@material-ui/core/Typography';
import ExpandLess from '@material-ui/icons/ExpandLess';
import ExpandMore from '@material-ui/icons/ExpandMore';
import React from 'react';

import { Specification } from '../../lib/specification';

interface Variable {
  name: string;
  location?: string;
  childFieldInfos?: Variable[];
  requirement: string;
  typeSignature: string;
  docString?: string | JSX.Element;
}

interface Props {
  title: string;
  variables: Variable[];
  hasLocation: boolean;
  specification: Specification;
}

export default function({
  title,
  variables,
  hasLocation,
  specification,
}: Props) {
  const hasBean = variables.some(
    (variable) =>
      !!variable.childFieldInfos && variable.childFieldInfos.length > 0,
  );
  return (
    <>
      <Typography variant="title">{title}</Typography>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Name</TableCell>
            {hasLocation && <TableCell>Location</TableCell>}
            <TableCell>Required</TableCell>
            <TableCell>Type</TableCell>
            <TableCell>Description</TableCell>
            {hasBean && <TableCell />}
          </TableRow>
        </TableHead>
        <TableBody>
          <FieldInfos
            indent={0}
            title={title}
            hasLocation={hasLocation}
            variables={variables}
            specification={specification}
            hasBean={hasBean}
            childIndex={0}
          />
        </TableBody>
      </Table>
      <Typography variant="body1" paragraph />
    </>
  );
}

interface State {
  isEmpty: boolean;
  isBeans: boolean[];
  openBeans: boolean[];
  colSpanLength: number;
  childStates: { [key: number]: State };
}

interface OwnProps {
  indent: number;
  hasBean: boolean;
  state?: State;
  setChildStateInParent?: (index: number, childState: State) => void;
  childIndex: number;
}

type FieldInfosProps = OwnProps & Props;

class FieldInfos extends React.Component<FieldInfosProps, State> {
  private static generateKey(pre: string): string {
    return `${pre}_${new Date().getTime()}`;
  }

  private static indentString(indent: number, s: string): string {
    return `${'\xa0'.repeat(indent)}${s}`;
  }

  private static formatLocation(s: string): string {
    return this.formatRequirement(s);
  }

  private static formatRequirement(s: string): string {
    const lowerCase = s.toLowerCase();
    if ('unspecified' === lowerCase) {
      return '-';
    }

    return lowerCase;
  }

  constructor(props: FieldInfosProps) {
    super(props);

    this.setChildStateInParent = this.setChildStateInParent.bind(this);

    if (props.state) {
      this.state = props.state;
      return;
    }

    const fieldInfos = props.variables;

    const isEmpty = fieldInfos.length === 0;
    let isBeans: boolean[] = [];
    let openBeans: boolean[] = [];
    if (!isEmpty) {
      isBeans = fieldInfos.map(
        ({ childFieldInfos }) =>
          !!childFieldInfos && childFieldInfos.length > 0,
      );

      openBeans = Array(fieldInfos.length).fill(false);
    }

    let colSpanLength = 4;
    if (this.props.hasLocation) {
      colSpanLength += 1;
    }
    if (this.props.hasBean) {
      colSpanLength += 1;
    }

    const childStates = {};
    this.state = {
      isEmpty,
      isBeans,
      openBeans,
      colSpanLength,
      childStates,
    };
  }

  public render() {
    return (
      <>
        {!this.state.isEmpty ? (
          this.props.variables.map((variable, index) => (
            <>
              <TableRow
                key={FieldInfos.generateKey(variable.name)}
                onClick={() => this.handleCollapse(index)}
              >
                <TableCell>
                  <code>
                    {FieldInfos.indentString(this.props.indent, variable.name)}
                  </code>
                </TableCell>
                {this.props.hasLocation &&
                  variable.location && (
                    <TableCell>
                      <code>
                        {FieldInfos.formatLocation(variable.location)}
                      </code>
                    </TableCell>
                  )}
                <TableCell>
                  <code>
                    {FieldInfos.formatRequirement(variable.requirement)}
                  </code>
                </TableCell>
                <TableCell>
                  <code>
                    {this.props.specification.getTypeSignatureHtml(
                      variable.typeSignature,
                    )}
                  </code>
                </TableCell>
                <TableCell>{variable.docString}</TableCell>
                {this.props.hasBean && this.showExpandIfBean(index)}
              </TableRow>
              {this.state.openBeans[index] && (
                <FieldInfos
                  {...this.props}
                  indent={this.props.indent + 2}
                  variables={variable.childFieldInfos!}
                  state={this.state.childStates[index]}
                  childIndex={index}
                  setChildStateInParent={this.setChildStateInParent}
                />
              )}
            </>
          ))
        ) : (
          <TableRow>
            <TableCell colSpan={this.state.colSpanLength}>
              There are no {this.props.title.toLowerCase()}
            </TableCell>
          </TableRow>
        )}
      </>
    );
  }

  private setChildStateInParent(index: number, childState: State) {
    this.state.childStates[index] = childState;
  }

  private showExpandIfBean(index: number) {
    return (
      <>
        <TableCell>
          {this.state.isBeans[index] &&
            (this.state.openBeans[index] ? <ExpandLess /> : <ExpandMore />)}
        </TableCell>
      </>
    );
  }

  private handleCollapse = (index: number) => {
    const state = this.state;
    if (!state.isBeans[index]) {
      return;
    }
    state.openBeans[index] = !state.openBeans[index];
    this.setState(state);

    if (this.props.setChildStateInParent) {
      this.props.setChildStateInParent(this.props.childIndex, state);
    }
  };
}
