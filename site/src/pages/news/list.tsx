import React, { useCallback } from 'react';

import ListAllLayout from '../../layouts/list-all';
import NewsLayout from '../../layouts/news';

import allNewsItems from '../../../gen-src/news-all.json';

export default (props: any) => {
  return (
    <ListAllLayout
      {...props}
      pageTitle="All past news items"
      allItems={allNewsItems}
      layout={NewsLayout}
      grouper={useCallback((pagePath) => {
        const startIdx = pagePath.lastIndexOf('/') + 1;
        return pagePath.substring(
          startIdx,
          Math.min(startIdx + 4, pagePath.length),
        );
      }, [])}
    />
  );
};
