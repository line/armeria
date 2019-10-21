import { Button } from '@material-ui/core';
import TextField from '@material-ui/core/TextField';
import React, { useState } from 'react';
import jsonPrettify from '../../lib/json-prettify';
import KeyValueTable from '../KeyValueTable';
import { Row, ValueListContext } from './valueListContext';

interface KeyValueEditorProps {
  defaultValue?: Row[];
}
enum DisplayType {
  KeyValue,
  Plain,
}

const KeyValueEditor: React.FunctionComponent<KeyValueEditorProps> = ({
  defaultValue,
}) => {
  const [displayType, setDisplayType] = useState(DisplayType.KeyValue);
  const [rowList, setRowList] = useState<Row[]>(
    defaultValue || [
      {
        index: 0,
        key: '',
        value: '',
      },
    ],
  );

  const DisplayTypeButton: React.FunctionComponent = () => {
    if (displayType === DisplayType.Plain) {
      return (
        <Button onClick={() => setDisplayType(DisplayType.KeyValue)}>
          Key Value Type
        </Button>
      );
    }

    return (
      <Button onClick={() => setDisplayType(DisplayType.Plain)}>
        Bulk Edit
      </Button>
    );
  };
  return (
    <ValueListContext.Provider value={[rowList, setRowList]}>
      <DisplayTypeButton />
      {displayType === DisplayType.KeyValue ? (
        <KeyValueTable defaultKeyValueList={rowList} />
      ) : (
        <TextField
          multiline
          fullWidth
          rows={8}
          inputProps={{
            className: 'code',
          }}
          onChange={(e) => {
            const obj = JSON.parse(e.target.value);
            setRowList(
              Object.keys(obj).map((v, i) => {
                return {
                  index: i,
                  key: v,
                  value: obj[v],
                };
              }),
            );
          }}
          value={jsonPrettify(
            JSON.stringify(
              rowList.reduce(
                (
                  acc: { [key: string]: string },
                  cur: { key: string; value: string },
                ) => {
                  acc[cur.key] = cur.value || '';
                  return acc;
                },
                {},
              ),
            ),
          )}
        />
      )}
    </ValueListContext.Provider>
  );
};
export default React.memo(KeyValueEditor);
