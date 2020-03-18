import React from 'react';
import { Table, Form, Radio, Checkbox, Tag, TreeSelect } from 'antd';

import { OutboundLink } from 'gatsby-plugin-google-analytics';
import {
  TranslationOutlined,
  PlaySquareOutlined,
  FileTextOutlined,
  CommentOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import styles from './article-list.module.less';

interface LocalizedText {
  [language: string]: string;
}

interface Article {
  title: LocalizedText;
  url: LocalizedText;
  author: LocalizedText;
  date: string;
  tags: string[];
  machineTranslation?: boolean;
}

interface ArticleListProps {
  dataSource: Article[];
}

const ArticleList: React.FC<ArticleListProps> = props => {
  const [language, setLanguage] = React.useState('en');
  const [tags, setTags] = React.useState([
    'articles',
    'slides',
    'knowledge',
    'experience',
  ]);
  const [
    includeMachineTranslated,
    setIncludeMachineTranslated,
  ] = React.useState(true);

  const columns = [
    {
      title: 'Title',
      dataIndex: 'title',
      key: 'title',
      className: styles.title,
      render: (e: any) => {
        let url: string = e.article.url[e.language];
        let machineTranslated = false;
        if (!url) {
          const startLanguage = ['en', 'ja', 'ko'].find(l => e.article.url[l]);
          if (startLanguage) {
            url = `https://translate.google.com/translate?sl=${startLanguage}&tl=${language}&u=${encodeURIComponent(
              e.article.url[startLanguage],
            )}`;
            machineTranslated = true;
          }
        }

        if (!url) {
          return e.localizedTitle;
        }

        return (
          <>
            {url ? (
              <OutboundLink href={url}>{e.localizedTitle}</OutboundLink>
            ) : (
              e.localizedTitle
            )}{' '}
            {e.article.tags
              .map((tag: string) => {
                switch (tag) {
                  case 'articles':
                    return <FileTextOutlined />;
                  case 'slides':
                    return <PlaySquareOutlined />;
                  case 'knowledge':
                    return <ExperimentOutlined />;
                  case 'experience':
                    return <CommentOutlined />;
                  default:
                    return <Tag key={tag}>{tag}</Tag>;
                }
              })
              .map((reactNode: React.ReactNode) => [reactNode, ' '])}
            {machineTranslated ? <TranslationOutlined /> : ''}
          </>
        );
      },
      sorter: (a: any, b: any) =>
        a.title.localizedTitle.localeCompare(b.title.localizedTitle),
    },
    {
      title: 'Date',
      dataIndex: 'date',
      key: 'date',
      sorter: (a: any, b: any) => a.date.localeCompare(b.date),
      className: styles.nowrap,
    },
    {
      title: 'Author',
      dataIndex: 'author',
      key: 'author',
      sorter: (a: any, b: any) => a.author.localeCompare(b.author),
      className: styles.nowrap,
    },
  ];

  const dataSource = convert(props.dataSource);

  function convert(articles: Article[]) {
    const formatTags = tags.filter(
      tag => tag === 'articles' || tag === 'slides',
    );
    const typeTags = tags.filter(
      tag => tag === 'knowledge' || tag === 'experience',
    );
    return articles.flatMap((e, i) => {
      // Skip if the tags do not match.
      if (
        !formatTags.find(tag => e.tags.includes(tag)) ||
        !typeTags.find(tag => e.tags.includes(tag))
      ) {
        return [];
      }

      // Skip if:
      // - Google Translate can't translate the article.
      // - A user unchecked the 'include machine-translagted' checkbox.
      if (
        (e.machineTranslation === false || !includeMachineTranslated) &&
        !e.url[language]
      ) {
        return [];
      }

      return {
        key: i,
        title: {
          localizedTitle: e.title[language] || e.title.en,
          article: e,
          language,
        },
        date: e.date,
        author: e.author[language] || e.author.en,
      };
    });
  }

  return (
    <>
      <Form className={styles.form} layout="inline">
        <Form.Item>
          <Radio.Group
            onChange={e => setLanguage(e.target.value)}
            value={language}
          >
            <Radio value="en">English</Radio>
            <Radio value="ja">日本語</Radio>
            <Radio value="ko">한국어</Radio>
          </Radio.Group>
        </Form.Item>
        <Form.Item>
          <Checkbox
            checked={includeMachineTranslated}
            onChange={() =>
              setIncludeMachineTranslated(!includeMachineTranslated)
            }
          >
            Include machine-translated / <TranslationOutlined />
          </Checkbox>
        </Form.Item>
        <Form.Item>
          <TreeSelect
            className={styles.tagsSelect}
            value={tags}
            onChange={values => {
              const filteredValues = values.filter(
                value => !value.startsWith('_'),
              );
              setTags(filteredValues);
            }}
            allowClear
            multiple
            treeDefaultExpandAll
          >
            <TreeSelect.TreeNode value="_1" title="Formats">
              <TreeSelect.TreeNode
                value="articles"
                title={
                  <>
                    <FileTextOutlined /> Articles
                  </>
                }
              />
              <TreeSelect.TreeNode
                value="slides"
                title={
                  <>
                    <PlaySquareOutlined /> Slides &amp; videos
                  </>
                }
              />
            </TreeSelect.TreeNode>
            <TreeSelect.TreeNode value="_2" title="Content types">
              <TreeSelect.TreeNode
                value="knowledge"
                title={
                  <>
                    <ExperimentOutlined /> Knowledge
                  </>
                }
              />
              <TreeSelect.TreeNode
                value="experience"
                title={
                  <>
                    <CommentOutlined /> Experience
                  </>
                }
              />
            </TreeSelect.TreeNode>
          </TreeSelect>
        </Form.Item>
      </Form>
      <Table
        columns={columns}
        dataSource={dataSource}
        pagination={false}
        size="middle"
        showSorterTooltip={false}
        scroll={{ x: true }}
        bordered
      />
    </>
  );
};

export default ArticleList;
