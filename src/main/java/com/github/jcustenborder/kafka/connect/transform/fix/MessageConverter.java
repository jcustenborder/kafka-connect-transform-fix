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

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.BooleanField;
import quickfix.CharField;
import quickfix.DoubleField;
import quickfix.Field;
import quickfix.FieldNotFound;
import quickfix.IntField;
import quickfix.Message;
import quickfix.StringField;
import quickfix.UtcDateOnlyField;
import quickfix.UtcTimeOnlyField;
import quickfix.UtcTimeStampField;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MessageConverter {
  private static final Logger log = LoggerFactory.getLogger(MessageConverter.class);
  private final Map<Class<? extends Message>, State> schemaCache = new HashMap<>();


  private State state(final Class<? extends Message> messageClass) {
    return this.schemaCache.computeIfAbsent(messageClass, aClass -> {
      String schemaName = messageClass.getName().replaceAll("^quickfix\\.", "");
      SchemaBuilder builder = SchemaBuilder.struct()
          .name(schemaName);

      Map<Integer, Class<?>> fields =
          Arrays.stream(aClass.getMethods())
              .filter(method -> "get".equals(method.getName()))
              .filter(method -> 1 == method.getParameterCount())
              .filter(method -> Field.class.isAssignableFrom(method.getParameterTypes()[0]))
              .map(Method::getReturnType)
              .sorted((Comparator<Class<?>>) (o1, o2) -> o1.getSimpleName().compareTo(o2.getSimpleName()))
              .collect(Collectors.toMap(
                  c -> {
                    try {
                      log.trace("state() - c = '{}'", c.getName());
                      return (Integer) c.getField("FIELD").get(null);
                    } catch (IllegalAccessException | NoSuchFieldException e) {
                      throw new DataException(e);
                    }
                  },
                  c -> c,
                  (u, v) -> {
                    throw new IllegalStateException(String.format("Duplicate key %s", u));
                  },
                  LinkedHashMap::new
              ));
      List<StructSetter> setters = new ArrayList<>(fields.size());
      for (Map.Entry<Integer, Class<?>> entry : fields.entrySet()) {
        final Integer fieldNumber = entry.getKey();
        final Class<?> fieldClass = entry.getValue();
        final String fieldName = fieldClass.getSimpleName();
        log.trace(
            "state() - fieldName = '{}' fieldClass = '{}'",
            fieldName,
            fieldClass.getName()
        );
        SchemaBuilder fieldSchemaBuilder;

        if (StringField.class.isAssignableFrom(fieldClass)) {
          fieldSchemaBuilder = SchemaBuilder.string();
          setters.add(new StringStructSetter(fieldName, fieldNumber));
        } else if (IntField.class.isAssignableFrom(fieldClass)) {
          fieldSchemaBuilder = SchemaBuilder.int32();
          setters.add(new IntStructSetter(fieldName, fieldNumber));
        } else if (BooleanField.class.isAssignableFrom(fieldClass)) {
          fieldSchemaBuilder = SchemaBuilder.bool();
          setters.add(new BooleanStructSetter(fieldName, fieldNumber));
        } else if (UtcTimeStampField.class.isAssignableFrom(fieldClass)) {
          fieldSchemaBuilder = Timestamp.builder();
          setters.add(new UtcTimeStampStructSetter(fieldName, fieldNumber));
        } else if (CharField.class.isAssignableFrom(fieldClass)) {
          fieldSchemaBuilder = SchemaBuilder.string();
          setters.add(new CharStructSetter(fieldName, fieldNumber));
        } else if (DoubleField.class.isAssignableFrom(fieldClass)) {
          fieldSchemaBuilder = SchemaBuilder.float64();
          setters.add(new DoubleStructSetter(fieldName, fieldNumber));
        } else if (UtcDateOnlyField.class.isAssignableFrom(fieldClass)) {
          fieldSchemaBuilder = org.apache.kafka.connect.data.Date.builder();
          setters.add(new UtcDateOnlyStructSetter(fieldName, fieldNumber));
        } else if (UtcTimeOnlyField.class.isAssignableFrom(fieldClass)) {
          fieldSchemaBuilder = org.apache.kafka.connect.data.Time.builder();
          setters.add(new UtcTimeOnlyStructSetter(fieldName, fieldNumber));
        } else {
          throw new DataException(
              String.format(
                  "Field %s is not supported. BaseClass = %s",
                  fieldClass.getSimpleName(),
                  fieldClass.getSuperclass()
              )
          );
        }

        fieldSchemaBuilder.optional();
        fieldSchemaBuilder.parameter("fix.field", fieldNumber.toString());
        Schema fieldSchema = fieldSchemaBuilder.build();
        builder.field(fieldName, fieldSchema);
      }
      Schema schema = builder.build();

      return State.of(schema, setters);
    });


  }


  public SchemaAndValue convert(final Message message) {
    final Class<? extends Message> messageClass = message.getClass();
    final State state = state(messageClass);
    final Struct struct = new Struct(state.schema);

    for (StructSetter structSetter : state.structSetters) {
      structSetter.apply(struct, message);
    }

    return new SchemaAndValue(struct.schema(), struct);
  }


  static abstract class StructSetter {
    private static final Logger log = LoggerFactory.getLogger(StructSetter.class);
    protected final String fieldName;
    protected final Integer fieldNumber;

    public StructSetter(String fieldName, Integer fieldNumber) {
      this.fieldName = fieldName;
      this.fieldNumber = fieldNumber;
    }

    public abstract void set(Struct struct, Message message);

    public void apply(Struct struct, Message message) {
      log.trace("apply() - fieldNumber = '{}' fieldName = '{}'", this.fieldNumber, this.fieldName);
      if (message.isSetField(this.fieldNumber)) {
        set(struct, message);
      } else {
        struct.put(this.fieldName, null);
      }
    }
  }

  static class StringStructSetter extends StructSetter {
    public StringStructSetter(String fieldName, Integer fieldNumber) {
      super(fieldName, fieldNumber);
    }

    @Override
    public void set(Struct struct, Message message) {
      final Object value;
      try {
        value = message.getString(this.fieldNumber);
      } catch (FieldNotFound fieldNotFound) {
        throw new DataException(fieldNotFound);
      }
      struct.put(this.fieldName, value);
    }
  }

  static class CharStructSetter extends StructSetter {
    public CharStructSetter(String fieldName, Integer fieldNumber) {
      super(fieldName, fieldNumber);
    }

    @Override
    public void set(Struct struct, Message message) {
      final Object value;
      try {
        value = ((Character) message.getChar(this.fieldNumber)).toString();
      } catch (FieldNotFound fieldNotFound) {
        throw new DataException(fieldNotFound);
      }
      struct.put(this.fieldName, value);
    }
  }

  static class IntStructSetter extends StructSetter {
    public IntStructSetter(String fieldName, Integer fieldNumber) {
      super(fieldName, fieldNumber);
    }

    @Override
    public void set(Struct struct, Message message) {
      final Object value;
      try {
        value = message.getInt(this.fieldNumber);
      } catch (FieldNotFound fieldNotFound) {
        throw new DataException(fieldNotFound);
      }
      struct.put(this.fieldName, value);
    }
  }

  static class BooleanStructSetter extends StructSetter {
    public BooleanStructSetter(String fieldName, Integer fieldNumber) {
      super(fieldName, fieldNumber);
    }

    @Override
    public void set(Struct struct, Message message) {
      final Object value;
      try {
        value = message.getBoolean(this.fieldNumber);
      } catch (FieldNotFound fieldNotFound) {
        throw new DataException(fieldNotFound);
      }
      struct.put(this.fieldName, value);
    }
  }

  static class UtcTimeStampStructSetter extends StructSetter {
    public UtcTimeStampStructSetter(String fieldName, Integer fieldNumber) {
      super(fieldName, fieldNumber);
    }

    @Override
    public void set(Struct struct, Message message) {
      final Object value;
      try {
        LocalDateTime localDateTime = message.getUtcTimeStamp(this.fieldNumber);
        value = Date.from(localDateTime.toInstant(ZoneOffset.UTC));
      } catch (FieldNotFound fieldNotFound) {
        throw new DataException(fieldNotFound);
      }
      struct.put(this.fieldName, value);
    }
  }

  static class UtcDateOnlyStructSetter extends StructSetter {
    public UtcDateOnlyStructSetter(String fieldName, Integer fieldNumber) {
      super(fieldName, fieldNumber);
    }

    @Override
    public void set(Struct struct, Message message) {
      final Object value;
      try {
        LocalDate localDate = message.getUtcDateOnly(this.fieldNumber);
        value = Date.from(localDate.atStartOfDay().toInstant(ZoneOffset.UTC));
      } catch (FieldNotFound fieldNotFound) {
        throw new DataException(fieldNotFound);
      }
      struct.put(this.fieldName, value);
    }
  }

  static class UtcTimeOnlyStructSetter extends StructSetter {
    public UtcTimeOnlyStructSetter(String fieldName, Integer fieldNumber) {
      super(fieldName, fieldNumber);
    }

    @Override
    public void set(Struct struct, Message message) {
      final Object value;
      try {
        LocalTime localTime = message.getUtcTimeOnly(this.fieldNumber);
        value = Date.from(localTime.atDate(LocalDate.ofEpochDay(0)).toInstant(ZoneOffset.UTC));
      } catch (FieldNotFound fieldNotFound) {
        throw new DataException(fieldNotFound);
      }
      struct.put(this.fieldName, value);
    }
  }

  static class DoubleStructSetter extends StructSetter {
    public DoubleStructSetter(String fieldName, Integer fieldNumber) {
      super(fieldName, fieldNumber);
    }

    @Override
    public void set(Struct struct, Message message) {
      final Object value;
      try {
        value = message.getDouble(this.fieldNumber);
      } catch (FieldNotFound fieldNotFound) {
        throw new DataException(fieldNotFound);
      }
      struct.put(this.fieldName, value);
    }
  }

  static class State {
    public final Schema schema;
    public final List<StructSetter> structSetters;


    State(Schema schema, List<StructSetter> structSetters) {
      this.schema = schema;
      this.structSetters = structSetters;
    }

    public static State of(Schema schema, List<StructSetter> structSetters) {
      return new State(schema, structSetters);
    }
  }
}
