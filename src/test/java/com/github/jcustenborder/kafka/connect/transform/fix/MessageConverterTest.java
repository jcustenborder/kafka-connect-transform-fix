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

import org.apache.kafka.connect.data.SchemaAndValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import quickfix.Message;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

public class MessageConverterTest {
  MessageConverter converter;

  @BeforeEach
  public void before() {
    this.converter = new MessageConverter();
  }

  @TestFactory
  public Stream<DynamicTest> convert() {
    Reflections reflections = new Reflections(new ConfigurationBuilder()
        .setUrls(ClasspathHelper.forJavaClassPath())
        .forPackages(this.getClass().getName())
        .addScanners(new ResourcesScanner())
    );

    Set<Class<? extends Message>> messageClasses = reflections.getSubTypesOf(Message.class);

    return messageClasses.stream()
        .map(c -> dynamicTest(c.getName(), () -> {
          Message message = c.newInstance();
          SchemaAndValue value = this.converter.convert(message);
          assertNotNull(value);
        }));
  }
}
