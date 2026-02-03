import '@ant-design/v5-patch-for-react-19'; //  Make antd work properly in React 19. See https://ant.design/docs/react/v5-for-19.
import { MailOutlined } from '@ant-design/icons';
import { Button, Input, message } from 'antd';
import { ButtonType } from 'antd/es/button';
import EmailValidator from 'email-validator';
import jsonp from 'jsonp';
import React, { useState, useCallback } from 'react';
import DOMPurify from 'dompurify';

import Emoji from './emoji';
import styles from './mailchimp.module.css';

interface MailchimpProps {
  url?: string;
  botCode?: string;
  buttonType?: ButtonType;
}

const Mailchimp: React.FC<MailchimpProps> = (givenProps) => {
  let props: MailchimpProps;
  if (!givenProps.url) {
    props = {
      url: 'https://github.us19.list-manage.com/subscribe/post?u=3447f8227584634e6ee046edf&id=852d70ccdc',
      botCode: 'b_3447f8227584634e6ee046edf_852d70ccdc',
      buttonType: givenProps.buttonType,
    };
  } else {
    props = givenProps;
  }

  const [email, setEmail] = useState('');
  const [sending, setSending] = useState(false);
  const [messageApi, contextHolder] = message.useMessage();
  const messageDuration = 5;

  return (
    <>
      {contextHolder}
      <Input.Search
        className={styles.signUpForm}
        type="email"
        prefix={<MailOutlined />}
        placeholder="Your e-mail"
        enterButton={
          <Button
            type={props.buttonType || 'default'}
            title="Sign up for our newsletters"
          >
            Subscribe
          </Button>
        }
        required
        aria-required
        value={email}
        loading={sending}
        onChange={useCallback((e) => setEmail(e.target.value), [])}
        onSearch={useCallback(
          (
            value: string,
            event:
              | React.SyntheticEvent<HTMLInputElement>
              | React.MouseEvent<HTMLElement>
              | React.KeyboardEvent<HTMLInputElement>,
          ) => {
            event.preventDefault();
            if (!EmailValidator.validate(email)) {
              messageApi.warning(
                'Please enter a valid e-mail address.',
                messageDuration,
              );
              return;
            }

            const url = `${props.url.replace(
              '/post?',
              '/post-json?',
            )}&EMAIL=${encodeURIComponent(email)}&${props.botCode}=`;

            setSending(true);
            jsonp(url, { param: 'c' }, (err: any, data: any) => {
              setSending(false);
              if (err || data.result !== 'success') {
                messageApi.error(
                  <span
                    // eslint-disable-next-line react/no-danger
                    dangerouslySetInnerHTML={{
                      __html: DOMPurify.sanitize(
                        data?.msg ||
                          'Failed to sign up. Please try again later.',
                      ),
                    }}
                  />,
                  messageDuration,
                );
              } else {
                setEmail('');
                messageApi.info(
                  <Emoji text="Thank you for signing up! 🙇" />,
                  messageDuration,
                );
              }
            });
          },
          [email, props.botCode, props.url, messageApi],
        )}
      />
    </>
  );
};

export default Mailchimp;
