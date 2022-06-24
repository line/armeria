/*
 * Copyright 2022 LINE Corporation
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

import React, { useEffect } from 'react';
// @ts-ignore
import mermaid from 'mermaid';

interface MermaidWrapperProps {
  docString: string;
}

interface MermaidProps {
  chart: string;
}

const Mermaid: React.FunctionComponent<MermaidProps> = ({ chart }) => {
  useEffect(() => {
    mermaid.contentLoaded();
  });

  const configs = {
    startOnLoad: true,
    theme: 'base',
    logLevel: 'fatal',
    securityLevel: 'strict',
    arrowMarkerAbsolute: false,
  };

  mermaid.initialize({ ...configs });

  if (!chart) {
    return <div />;
  }

  return <div className="mermaid">{chart}</div>;
};

const MermaidWrapper: React.FunctionComponent<MermaidWrapperProps> = ({
  docString,
}) => {
  return <Mermaid chart={docString} />;
};

export default React.memo(MermaidWrapper);
