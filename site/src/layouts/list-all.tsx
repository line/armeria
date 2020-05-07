import { RouteComponentProps } from '@reach/router';
import { Link } from 'gatsby';
import React from 'react';

import styles from './list-all.module.less';

interface ListAllLayoutProps extends RouteComponentProps {
  pageContext: any;
  pageTitle: string;
  allItems: { [pagePath: string]: string };
  grouper: (pagePath: string) => string;
  layout: React.FC;
}

const ListAllLayout: React.FC<ListAllLayoutProps> = props => {
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
      <h1>{props.pageTitle}</h1>
      {Object.entries(groupedItems).map(([groupName, items]) => (
        <span key={groupName}>
          <h2 id={groupName}>{groupName}</h2>
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
