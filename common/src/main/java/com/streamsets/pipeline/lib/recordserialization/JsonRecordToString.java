/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.recordserialization;

import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.lib.util.JsonUtil;

import java.util.Map;

public class JsonRecordToString implements RecordToString {

  @Override
  public void setFieldPathToNameMapping(Map<String, String> fieldPathToNameMap) {
    if(fieldPathToNameMap != null && !fieldPathToNameMap.isEmpty()) {
      throw new IllegalArgumentException(
        "Field path to name mapping configuration is not expected for JsonRecordToString.");
    }
  }

  @Override
  public String toString(Record record) throws StageException {
    return JsonUtil.jsonRecordToString(record);
  }

}
