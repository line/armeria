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

import Typography from '@material-ui/core/Typography';
import React from 'react';
import { RouteComponentProps } from 'react-router-dom';

import VariableList from '../../components/VariableList';

import {
  packageName,
  simpleName,
  Specification,
} from '../../lib/specification';

import Section from '../../components/Section';
import Description from '../../components/Description';

interface OwnProps {
  specification: Specification;
}

type Props = OwnProps & RouteComponentProps<{ name: string }>;

const StructPage: React.FunctionComponent<Props> = ({
  specification,
  match,
}) => {
  const data = specification.getStructByName(match.params.name);
  if (!data) {
    return <>Not found.</>;
  }

  return (
    <>
      <Typography variant="h5">
        <code>{simpleName(data.name)}</code>
      </Typography>
      <Typography variant="subtitle1" paragraph>
        <code>{packageName(data.name)}</code>
      </Typography>
      {data.descriptionInfo?.docString && (
        <Section>
          <Description descriptionInfo={data.descriptionInfo} />
        </Section>
      )}
      <Section>
        <VariableList
          title="Fields"
          variables={data.fields}
          specification={specification}
        />
      </Section>
    </>
  );
};

export default React.memo(StructPage);
