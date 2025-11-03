import React from 'react';
import { DownOutlined } from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { Button, Dropdown } from 'antd';
import Link from '@docusaurus/Link';

import styles from './blog-language-selector.module.css';

const items: MenuProps['items'] = [
  {
    key: 'en',
    label: <Link to="/blog">English</Link>,
  },
  {
    key: 'ja',
    label: <Link to="/blog/ja">日本語</Link>,
  },
  {
    key: 'ko',
    label: <Link to="/blog/ko">한국어</Link>,
  },
];

const BlogLanguageSelector: React.FC = () => {
  const [selected, setSelected] = React.useState(items[0]);

  React.useEffect(() => {
    if (typeof window !== 'undefined') {
      const path = window.location.pathname;
      if (path.startsWith('/blog/ja')) {
        setSelected(items[1]);
      } else if (path.startsWith('/blog/ko')) {
        setSelected(items[2]);
      } else {
        setSelected(items[0]);
      }
    }
  }, []);

  const handleMenuClick: MenuProps['onClick'] = (e) => {
    const found = items.find((item) => item?.key === e.key);
    if (found) {
      setSelected(found);
    }
  };

  return (
    <Dropdown
      menu={{
        items,
        onClick: handleMenuClick,
        selectable: true,
        defaultSelectedKeys: [selected?.key],
      }}
    >
      <Button
        onClick={(e) => e.preventDefault()}
        size="small"
        className={styles.blogLanguageSelector}
      >
        {selected?.label}
        <DownOutlined />
      </Button>
    </Dropdown>
  );
};

export default BlogLanguageSelector;
