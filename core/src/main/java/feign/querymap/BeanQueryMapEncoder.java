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
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * the query map will be generated using java beans accessible getter property as query parameter
 * names.
 *
 * eg: "/uri?name={name}&number={number}"
 *
 * order of included query parameters not guaranteed, and as usual, if any value is null, it will be
 * left out
 * 该类 相比 FieldQueryMapEncoder 好像 范围要小些 只有携带Get的字段才会设置到 Map中
 */
public class BeanQueryMapEncoder implements QueryMapEncoder {
  private final Map<Class<?>, ObjectParamMetadata> classToMetadata =
      new HashMap<Class<?>, ObjectParamMetadata>();

  @Override
  public Map<String, Object> encode(Object object) throws EncodeException {
    try {
      ObjectParamMetadata metadata = getMetadata(object.getClass());
      Map<String, Object> propertyNameToValue = new HashMap<String, Object>();
      for (PropertyDescriptor pd : metadata.objectProperties) {
        Object value = pd.getReadMethod().invoke(object);
        if (value != null && value != object) {
          propertyNameToValue.put(pd.getName(), value);
        }
      }
      return propertyNameToValue;
    } catch (IllegalAccessException | IntrospectionException | InvocationTargetException e) {
      throw new EncodeException("Failure encoding object into query map", e);
    }
  }

  private ObjectParamMetadata getMetadata(Class<?> objectType) throws IntrospectionException {
    ObjectParamMetadata metadata = classToMetadata.get(objectType);
    if (metadata == null) {
      metadata = ObjectParamMetadata.parseObjectType(objectType);
      classToMetadata.put(objectType, metadata);
    }
    return metadata;
  }

  /**
   * 对象参数 元数据
   */
  private static class ObjectParamMetadata {

    private final List<PropertyDescriptor> objectProperties;

    private ObjectParamMetadata(List<PropertyDescriptor> objectProperties) {
      this.objectProperties = Collections.unmodifiableList(objectProperties);
    }

    private static ObjectParamMetadata parseObjectType(Class<?> type)
        throws IntrospectionException {
      List<PropertyDescriptor> properties = new ArrayList<PropertyDescriptor>();

      for (PropertyDescriptor pd : Introspector.getBeanInfo(type).getPropertyDescriptors()) {
        boolean isGetterMethod = pd.getReadMethod() != null && !"class".equals(pd.getName());
        if (isGetterMethod) {
          properties.add(pd);
        }
      }

      return new ObjectParamMetadata(properties);
    }
  }

  public static void main(String[] args) {
    BeanQueryMapEncoder beanQueryMapEncoder = new BeanQueryMapEncoder();
    beanQueryMapEncoder.encode(new A());
  }
  static class A {
    private int age;
    private String name;

    public int getAge() {
      return age;
    }

    public void setAge(int age) {
      this.age = age;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getName() {

      return name;
    }
  }
}
