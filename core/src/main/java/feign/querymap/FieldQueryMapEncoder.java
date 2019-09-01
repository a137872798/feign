/**
 * Copyright 2012-2019 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign.querymap;

import feign.QueryMapEncoder;
import feign.codec.EncodeException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * the query map will be generated using member variable names as query parameter names.
 *
 * eg: "/uri?name={name}&number={number}"
 *
 * order of included query parameters not guaranteed, and as usual, if any value is null, it will be
 * left out
 * 该对象是 将 Object的 属性和对应的值 保存到map中
 */
public class FieldQueryMapEncoder implements QueryMapEncoder {

  /**
   * 应该是 单例模式 下的缓存
   */
  private final Map<Class<?>, ObjectParamMetadata> classToMetadata =
      new HashMap<Class<?>, ObjectParamMetadata>();

  /**
   * 编码
   * @param object the object to encode
   * @return
   * @throws EncodeException
   */
  @Override
  public Map<String, Object> encode(Object object) throws EncodeException {
    try {
      ObjectParamMetadata metadata = getMetadata(object.getClass());
      Map<String, Object> fieldNameToValue = new HashMap<String, Object>();
      for (Field field : metadata.objectFields) {
        Object value = field.get(object);
        if (value != null && value != object) {
          fieldNameToValue.put(field.getName(), value);
        }
      }
      return fieldNameToValue;
    } catch (IllegalAccessException e) {
      throw new EncodeException("Failure encoding object into query map", e);
    }
  }

  /**
   * 将对象的属性抽出来生成 元数据
   * @param objectType
   * @return
   */
  private ObjectParamMetadata getMetadata(Class<?> objectType) {
    ObjectParamMetadata metadata = classToMetadata.get(objectType);
    if (metadata == null) {
      metadata = ObjectParamMetadata.parseObjectType(objectType);
      classToMetadata.put(objectType, metadata);
    }
    return metadata;
  }

  /**
   * Object 元数据对象
   */
  private static class ObjectParamMetadata {

    /**
     * 该对象内部的字段
     */
    private final List<Field> objectFields;

    private ObjectParamMetadata(List<Field> objectFields) {
      this.objectFields = Collections.unmodifiableList(objectFields);
    }

    private static ObjectParamMetadata parseObjectType(Class<?> type) {
      List<Field> allFields = new ArrayList();

      for (Class currentClass = type; currentClass != null; currentClass =
          currentClass.getSuperclass()) {
        // 将目标类 包括 父类的 所有属性添加到 Collection 中
        Collections.addAll(allFields, currentClass.getDeclaredFields());
      }

      // 去除掉 编译器 合成的属性
      return new ObjectParamMetadata(allFields.stream()
          .filter(field -> !field.isSynthetic())
          .peek(field -> field.setAccessible(true))
          .collect(Collectors.toList()));
    }
  }
}
