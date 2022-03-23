import { Alert as AntdAlert } from 'antd';
import React, { PropsWithChildren, ReactNode } from 'react';
import { InfoCircleOutlined, WarningOutlined } from '@ant-design/icons';

interface AlertProps {
  type: 'success' | 'info' | 'warning' | 'error';
  showIcon?: boolean;
  style?: React.CSSProperties;
  className?: string;
  icon?: React.ReactNode;
}

const Alert = (props: PropsWithChildren<AlertProps>): JSX.Element => (
  <AntdAlert
    type={props.type}
    showIcon={props.showIcon}
    style={props.style}
    className={props.className}
    icon={props.icon}
    message={<span>{props.children}</span>}
  />
);

interface TipOrWarningProps {
  children: ReactNode;
}

const Tip = (props: TipOrWarningProps): JSX.Element => (
  <Alert type="info" icon={<InfoCircleOutlined />} showIcon>
    {props.children}
  </Alert>
);
const Warning = (props: TipOrWarningProps): JSX.Element => (
  <Alert type="warning" icon={<WarningOutlined />} showIcon>
    {props.children}
  </Alert>
);

export { Alert, Tip, Warning };
