/*
import { Button } from '@material-ui/core';
import TextField from '@material-ui/core/TextField';
import parseLoosely from 'jsonic';
import React, { useState } from 'react';
import jsonPrettify from '../../lib/json-prettify';
import KeyValueTable from '../KeyValueTable';
import { CreateDefaultRow, Row, ValueListContext } from './valueListContext';

interface Props {
  defaultValue?: Row[];
  keyName?: string;
  valueName?: string;
}
enum DisplayType {
  KeyValue,
  Plain,
}

const KeyValueEditor: React.FunctionComponent<Props> = ({
  defaultValue,
  keyName = 'Key',
  valueName = 'Value',
}) => {
  const [displayType, setDisplayType] = useState(DisplayType.KeyValue);
  const [rowList, setRowList] = useState<Row[]>(
    defaultValue || [CreateDefaultRow()],
  );
  const [bulkString, setBulkString] = useState(JSON.stringify(rowList));

  const DisplayTypeButton: React.FunctionComponent = () => {
    if (displayType === DisplayType.Plain) {
      return (
        <Button
          onClick={() => {
            let newRowList = null;
            try {
              const obj = parseLoosely(bulkString);
              newRowList = Object.keys(obj).map((v) => {
                return {
                  key: v,
                  value: obj[v],
                };
              });

              rowList.push(CreateDefaultRow());
            } catch (e) {
              newRowList = [CreateDefaultRow()];
            }

            setRowList(newRowList);
            setDisplayType(DisplayType.KeyValue);
          }}
        >
          Key Value Type
        </Button>
      );
    }

    return (
      <Button
        onClick={() => {
          setBulkString(
            jsonPrettify(
              JSON.stringify(
                rowList.reduce(
                  (
                    acc: { [key: string]: string },
                    cur: { key: string; value: string },
                  ) => {
                    if (cur.key || cur.value) {
                      acc[cur.key || ''] = cur.value || '';
                    }
                    return acc;
                  },
                  {},
                ),
              ),
            ),
          );
          setDisplayType(DisplayType.Plain);
        }}
      >
        Bulk Edit
      </Button>
    );
  };
  return (
    <ValueListContext.Provider value={[rowList, setRowList]}>
      <DisplayTypeButton />
      {displayType === DisplayType.KeyValue ? (
        <KeyValueTable
          defaultKeyValueList={rowList}
          keyName={keyName}
          valueName={valueName}
        />
      ) : (
        <TextField
          multiline
          fullWidth
          rows={8}
          inputProps={{
            className: 'code',
          }}
          onChange={(e) => {
            setBulkString(e.target.value);
          }}
          value={bulkString}
        />
      )}
    </ValueListContext.Provider>
  );
};
export default React.memo(KeyValueEditor);
*/
