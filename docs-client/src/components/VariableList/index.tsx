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
import React, { useEffect, useReducer, useState } from 'react';

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

interface OwnProps {
  indent: number;
  hasBean: boolean;
  childIndex: number;
}

type FieldInfosProps = OwnProps & Props;

const generateKey = (pre: string) => `${pre}_${new Date().getTime()}`;

const indentString = (indent: number, s: string) =>
  `${'\xa0'.repeat(indent)}${s}`;

const formatRequirement = (s: string) => {
  const lowerCase = s.toLowerCase();
  if ('unspecified' === lowerCase) {
    return '-';
  }

  return lowerCase;
};

const formatLocation = (s: string) => formatRequirement(s);

interface FieldInfoProps {
  indent: number;
  childFields: Variable[];
}

const FieldInfo: React.FunctionComponent<FieldInfosProps> = (props) => {
  const [isExpanded, toggleIsExpanded] = useReducer(
    (current) => !current,
    false,
  );
  return (
      <>

      </>
  )
};

const FieldInfos: React.FunctionComponent<FieldInfosProps> = (props) => {
  const {
    variables,
    hasLocation,
    hasBean,
    indent,
    specification,
    title,
  } = props;

  const [isEmpty, setIsEmpty] = useState(false);
  const [isBeans, setIsBeans] = useState<boolean[]>([]);
  const [openBeans, toggleOpenBean] = useReducer(
    (current: boolean[], index: number) => {
      if (!isBeans[index]) {
        return current;
      }
      const newValue = [...current];
      newValue[index] = !newValue[index];
      return newValue;
    },
    [],
  );
  const [colSpanLength, setColSpanLength] = useState(4);

  useEffect(() => {
    const fieldInfos = variables;

    const initialIsEmpty = fieldInfos.length === 0;
    setIsEmpty(initialIsEmpty);
    if (!initialIsEmpty) {
      setIsBeans(
        fieldInfos.map(
          ({ childFieldInfos }) =>
            !!childFieldInfos && childFieldInfos.length > 0,
        ),
      );
    }

    let colSpanLengthDelta = 0;
    if (hasLocation) {
      colSpanLengthDelta += 1;
    }
    if (hasBean) {
      colSpanLengthDelta += 1;
    }
    setColSpanLength(4 + colSpanLengthDelta);
  }, [variables]);

  return (
    <>
      {!isEmpty ? (
        variables.map((variable, index) => (
          <React.Fragment key={generateKey(variable.name)}>
            <TableRow onClick={() => toggleOpenBean(index)}>
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
              <TableCell>{variable.docString}</TableCell>
              {hasBean && (
                <>
                  <TableCell>
                    {isBeans[index] &&
                      (openBeans[index] ? <ExpandLess /> : <ExpandMore />)}
                  </TableCell>
                </>
              )}
            </TableRow>
            {openBeans[index] && (
              <FieldInfos
                {...props}
                indent={indent + 2}
                variables={variable.childFieldInfos!}
                childIndex={index}
              />
            )}
          </React.Fragment>
        ))
      ) : (
        <TableRow>
          <TableCell colSpan={colSpanLength}>
            There are no {title.toLowerCase()}
          </TableCell>
        </TableRow>
      )}
    </>
  );
};

const VariableList: React.FunctionComponent<Props> = ({
  title,
  variables,
  hasLocation,
  specification,
}) => {
  const hasBean = variables.some(
    (variable) =>
      !!variable.childFieldInfos && variable.childFieldInfos.length > 0,
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
      <Typography variant="body2" paragraph />
    </>
  );
};

export default React.memo(VariableList);
