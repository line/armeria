import { RouteComponentProps } from '@reach/router';
import { Typography } from 'antd';
import { Link } from 'gatsby';
import React from 'react';

import styles from './list-all.module.less';

const { Title } = Typography;

interface ListAllLayoutProps extends RouteComponentProps {
  pageContext: any;
  pageTitle: string;
  allItems: { [pagePath: string]: string };
  grouper: (pagePath: string) => string;
  layout: React.FC;
}

const ListAllLayout: React.FC<ListAllLayoutProps> = (props) => {
  const groupedItems: {
    [groupName: string]: { [pagePath: string]: string };
  } = {};

  Object.entries(props.allItems).forEach(([pagePath, pageTitle]) => {
    const groupName = props.grouper(pagePath);
    if (!groupedItems[groupName]) {
      groupedItems[groupName] = {};
    }
    groupedItems[groupName][pagePath] = pageTitle;
  });

  const Layout = props.layout;

  return (
    <Layout {...props}>
      <Title level={1}>{props.pageTitle}</Title>
      {Object.entries(groupedItems).map(([groupName, items]) => (
        <span key={groupName}>
          <Title id={groupName} level={2}>
            {groupName}
          </Title>
          <ul className={styles.itemList}>
            {Object.entries(items).map(([pagePath, pageTitle]) => (
              <li key={pagePath}>
                <Link to={pagePath}>{pageTitle}</Link>
              </li>
            ))}
          </ul>
        </span>
      ))}
      {props.children}
    </Layout>
  );
};

export default ListAllLayout;
