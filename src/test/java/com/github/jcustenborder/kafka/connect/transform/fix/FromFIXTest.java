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

import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.jcustenborder.kafka.connect.utils.jackson.ObjectMapperFactory;
import com.google.common.collect.ImmutableMap;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.stream.Stream;

import static com.github.jcustenborder.kafka.connect.utils.AssertStruct.assertStruct;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

public class FromFIXTest {
  private static final Logger log = LoggerFactory.getLogger(FromFIXTest.class);
  File testsPath = new File("src/test/resources/com/github/jcustenborder/kafka/connect/transform/fix");
  FromFIX.Value transform;

  @BeforeEach
  public void before() {
    this.transform = new FromFIX.Value();
    this.transform.configure(
        ImmutableMap.of()
    );
  }

  @BeforeAll
  public static void beforeAll() {
    ObjectMapperFactory.INSTANCE.configure(SerializationFeature.INDENT_OUTPUT, true);
  }

  @AfterEach
  public void after() {
    this.transform.close();
  }

  //TODO: Look at more examples. https://www.nyse.com/publicdocs/nyse/markets/nyse/NYSE_CCG_FIX_Sample_Messages.pdf

  @TestFactory
  public Stream<DynamicTest> apply() {
    ObjectMapperFactory.INSTANCE.configure(SerializationFeature.INDENT_OUTPUT, true);
    return Arrays.stream(
        this.testsPath.listFiles()
    )
        .filter(File::isFile)
        .sorted()
        .map(f -> dynamicTest(f.getName(), () -> {
          TestCase testCase = ObjectMapperFactory.INSTANCE.readValue(f, TestCase.class);
          log.trace(ObjectMapperFactory.INSTANCE.writeValueAsString(testCase.message));
          final ConnectRecord inputRecord = new SinkRecord(
              "test",
              1,
              null,
              null,
              Schema.STRING_SCHEMA,
              testCase.message,
              new Date().getTime()
          );
          final ConnectRecord outputRecord = this.transform.apply(inputRecord);
          assertTrue(outputRecord.value() instanceof Struct, "outputRecord.value() should be a struct.");
          Struct actual = (Struct) outputRecord.value();
          assertStruct(testCase.struct, actual);
        }));
  }

  @Test
  public void invalidMessage() {
    DataException exception = assertThrows(DataException.class, () -> {
      String invalidMessage = "8=FIX.4.2^A 9=145^A 35=D^A 34=4^A 49=ABC_DEFG01^A 52=20090323-15:40:29^A 56=CCG^A" +
          "115=XYZ^A 11=NF 0542/03232009^A 54=1^A 38=100^A 55=CVS^A 40=1^A 59=0^A 47=A^A" +
          "60=20090323-15:40:29^A 21=1^A 207=N^A 10=139^A ";
      invalidMessage = invalidMessage.replace("^A ", "\0001");
      final ConnectRecord inputRecord = new SinkRecord(
          "test",
          1,
          null,
          null,
          Schema.STRING_SCHEMA,
          invalidMessage,
          new Date().getTime()
      );
      this.transform.apply(inputRecord);
    });
  }
}
