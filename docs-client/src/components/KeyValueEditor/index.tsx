import { Button } from '@material-ui/core';
import TextField from '@material-ui/core/TextField';
import React, { useState } from 'react';
import jsonPrettify from '../../lib/json-prettify';
import parseLoosely from 'jsonic';
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
  const [isSnackbarOpen, setSnackbarOpen] = useState(false);
  const [rowList, setRowList] = useState<Row[]>(
      defaultValue || [
        {
          key: '',
          value: '',
        },
      ],
  );
  const [bulkString, setBulkString] = useState(JSON.stringify(rowList));

  const DisplayTypeButton: React.FunctionComponent = () => {
    if (displayType === DisplayType.Plain) {
      return (
          <Button onClick={() => {
            try {
              const obj = parseLoosely(bulkString);
              setRowList(
                  Object.keys(obj).map((v) => {
                    return {
                      key: v,
                      value: obj[v],
                    };
                  }),
              );
            }catch(e){
              //catch and make browser continue running JS
              //there's no problem skip json syntax error in editor
              console.log(e);
            }
            setDisplayType(DisplayType.KeyValue);
          }}>
            Key Value Type
          </Button>
      );
    }

    return (
        <Button onClick={() => {
          setBulkString(jsonPrettify(
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
              )
          )
          );
          setDisplayType(DisplayType.Plain);
        }}>
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
                  setBulkString(e.target.value)
                }}
                value={bulkString}
            />
        )}
      </ValueListContext.Provider>
  );
};
export default React.memo(KeyValueEditor);
