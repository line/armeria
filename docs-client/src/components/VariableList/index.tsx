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
import React, { useReducer } from 'react';

import { makeStyles } from '@material-ui/core';
import { Specification } from '../../lib/specification';

const useStyles = makeStyles({
  hidden: {
    display: 'none',
  },
});

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
  specification: Specification;
}

interface OwnProps {
  indent: number;
  hasLocation: boolean;
  hidden?: boolean;
}

type FieldInfosProps = OwnProps & Props;

interface FieldInfoProps {
  hasLocation: boolean;
  hidden?: boolean;
  indent: number;
  variable: Variable;
  specification: Specification;
  title: string;
}

const indentString = (indent: number, s: string): string => {
  return `${'\xa0'.repeat(indent)}${s}`;
};

const formatRequirement = (s: string): string => {
  const lowerCase = s.toLowerCase();
  if (lowerCase === 'unspecified') {
    return '-';
  }

  return lowerCase;
};

// Same formatting for location as requirement at least for now.
const formatLocation = formatRequirement;

const FieldInfo: React.FunctionComponent<FieldInfoProps> = ({
  hasLocation,
  hidden,
  indent,
  specification,
  title,
  variable,
}) => {
  const styles = useStyles();

  const [expanded, toggleExpanded] = useReducer((value) => !value, false);

  const hasChildren =
    variable.childFieldInfos && variable.childFieldInfos.length > 0;

  return (
    <>
      <TableRow
        onClick={toggleExpanded}
        className={hidden ? styles.hidden : ''}
      >
        <TableCell>
          <code>{indentString(indent, variable.name)}</code>
        </TableCell>
        {hasLocation && variable.location && (
          <TableCell>
            <code>{formatLocation(variable.location)}</code>
          </TableCell>
        )}
        <TableCell>
          <code>{formatRequirement(variable.requirement)}</code>
        </TableCell>
        <TableCell>
          <code>
            {specification.getTypeSignatureHtml(variable.typeSignature)}
          </code>
        </TableCell>
        <TableCell>
          <pre>{variable.docString}</pre>
        </TableCell>
        {hasChildren && (
          <TableCell>{expanded ? <ExpandLess /> : <ExpandMore />}</TableCell>
        )}
      </TableRow>
      {hasChildren && (
        // TODO(minwoox) fix circular usage of FieldInfo and FieldInfos
        // eslint-disable-next-line @typescript-eslint/no-use-before-define
        <FieldInfos
          hasLocation={hasLocation}
          hidden={hidden || !expanded}
          indent={indent + 2}
          variables={variable.childFieldInfos!}
          specification={specification}
          title={title}
        />
      )}
    </>
  );
};

const FieldInfos: React.FunctionComponent<FieldInfosProps> = (props) => {
  const styles = useStyles();

  const isEmpty = props.variables.length === 0;

  let colSpanLength = 4;

  if (props.variables.some((variable) => !!variable.location)) {
    colSpanLength += 1;
  }

  if (
    props.variables.some(
      (variable) =>
        variable.childFieldInfos && variable.childFieldInfos.length > 0,
    )
  ) {
    colSpanLength += 1;
  }
  return (
    // eslint-disable-next-line react/jsx-no-useless-fragment
    <>
      {!isEmpty ? (
        props.variables.map((variable, index) => (
          <FieldInfo
            hasLocation={props.hasLocation}
            hidden={props.hidden}
            // eslint-disable-next-line react/no-array-index-key
            key={`${variable.name}-${index}`}
            indent={props.indent}
            variable={variable}
            specification={props.specification}
            title={props.title}
          />
        ))
      ) : (
        <TableRow className={props.hidden ? styles.hidden : ''}>
          <TableCell colSpan={colSpanLength}>
            There are no {props.title.toLowerCase()}
          </TableCell>
        </TableRow>
      )}
    </>
  );
};

export default ({ title, variables, specification }: Props) => {
  const hasBean = variables.some(
    (variable) =>
      !!variable.childFieldInfos && variable.childFieldInfos.length > 0,
  );

  const hasLocation = variables.some(
    (variable) =>
      variable.location &&
      variable.location.length > 0 &&
      variable.location !== 'UNSPECIFIED',
  );

  return (
    <>
      <Typography variant="h6">{title}</Typography>
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
            hasLocation={hasLocation}
            indent={0}
            title={title}
            variables={variables}
            specification={specification}
          />
        </TableBody>
      </Table>
      <Typography variant="body2" paragraph />
    </>
  );
};
