/**
 * Copyright © 2017 Jeremy Custenborder (jcustenborder@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jcustenborder.kafka.connect.transform.fix;

import com.github.jcustenborder.kafka.connect.utils.config.Description;
import com.github.jcustenborder.kafka.connect.utils.config.Title;
import com.github.jcustenborder.kafka.connect.utils.transformation.BaseKeyValueTransformation;
import com.google.common.base.Strings;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.ConfigError;
import quickfix.DataDictionary;
import quickfix.DefaultMessageFactory;
import quickfix.InvalidMessage;
import quickfix.Message;
import quickfix.MessageUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Title("FromFix(value)")
@Description("This transformation is used to read FIX encoded messages and convert them to a " +
    "representation of FIX that downstream systems can understand.")
public abstract class FromFIX<R extends ConnectRecord<R>> extends BaseKeyValueTransformation<R> {
  private static final Logger log = LoggerFactory.getLogger(FromFIX.class);

  protected FromFIX(boolean isKey) {
    super(isKey);
  }

  @Override
  public ConfigDef config() {
    return new ConfigDef();
  }

  @Override
  public void close() {

  }

  DefaultMessageFactory messageFactory;
  Map<String, DataDictionary> dictionaryCache = new HashMap<>();

  DataDictionary dataDictionary(String fixVersion) {
    if (Strings.isNullOrEmpty(fixVersion)) {
      throw new DataException("Could not determine the FIX version.");
    }

    return this.dictionaryCache.computeIfAbsent(fixVersion, s -> {
      String fileName = "/" + fixVersion.replace(".", "") + ".xml";
      try (InputStream inputStream = this.getClass().getResourceAsStream(fileName)) {
        return new DataDictionary(inputStream);
      } catch (IOException | ConfigError e) {
        DataException dataException = new DataException(
            String.format(
                "Exception while loading file '%s'. FixVersion = '%s'.",
                fileName,
                fixVersion
            )
        );
        dataException.initCause(e);
        throw dataException;
      }
    });
  }

  private MessageConverter converter = new MessageConverter();

  @Override
  protected SchemaAndValue processString(R record, Schema inputSchema, String input) {
    try {
      log.trace("processString() - input = '{}'", input);
      final String fixVersion = MessageUtils.getStringField(input, 8);
      log.debug("processString() - fixVersion = '{}'", fixVersion);
      final DataDictionary dataDictionary = dataDictionary(fixVersion);
      Message message = MessageUtils.parse(this.messageFactory, dataDictionary, input);
      log.trace("processString() - message = '{}'", message);
      return this.converter.convert(message);
    } catch (InvalidMessage invalidMessage) {
      throw new DataException(invalidMessage);
    }
  }


  @Override
  public void configure(Map<String, ?> settings) {
    this.messageFactory = new DefaultMessageFactory();
  }

  public static class Key<R extends ConnectRecord<R>> extends FromFIX<R> {
    public Key() {
      super(true);
    }
  }

  public static class Value<R extends ConnectRecord<R>> extends FromFIX<R> {
    public Value() {
      super(false);
    }
  }
}
