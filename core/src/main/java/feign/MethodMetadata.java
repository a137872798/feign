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
package feign;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import feign.Param.Expander;

/**
 * 方法 元数据  一个普通的javaBean 对象
 */
public final class MethodMetadata implements Serializable {

  private static final long serialVersionUID = 1L;
  /**
   * 唯一标识 通过class+method 生成
   */
  private String configKey;
  /**
   * 返回类型
   */
  private transient Type returnType;

  /**
   * 参数类型是 URL 的下标
   */
  private Integer urlIndex;
  /**
   * 参数类型是 Body 的下标
   */
  private Integer bodyIndex;
  /**
   * 携带 @HeaderMap 注解的参数下标  一个method 只允许有一个参数携带该注解
   */
  private Integer headerMapIndex;
  /**
   * 代表第几个参数 携带 @QueryMap 注解
   */
  private Integer queryMapIndex;
  /**
   * 代表 @QueryMap 对应的参数是否需要编码
   */
  private boolean queryMapEncoded;
  private transient Type bodyType;
  private RequestTemplate template = new RequestTemplate();
  /**
   *  携带 @Param 的参数名
   */
  private List<String> formParams = new ArrayList<String>();
  /**
   * 该容器是 维护 参数下标 和 @Param 注解内 name 的容器
   */
  private Map<Integer, Collection<String>> indexToName =
      new LinkedHashMap<Integer, Collection<String>>();
  /**
   * 维护参数下标 与对应拓展类的容器
   */
  private Map<Integer, Class<? extends Expander>> indexToExpanderClass =
      new LinkedHashMap<Integer, Class<? extends Expander>>();
  /**
   * 维护参数下标 与 是否需要编码的 容器
   */
  private Map<Integer, Boolean> indexToEncoded = new LinkedHashMap<Integer, Boolean>();
  private transient Map<Integer, Expander> indexToExpander;

  MethodMetadata() {}

  /**
   * Used as a reference to this method. For example, {@link Logger#log(String, String, Object...)
   * logging} or {@link ReflectiveFeign reflective dispatch}.
   *
   * @see Feign#configKey(Class, java.lang.reflect.Method)
   */
  public String configKey() {
    return configKey;
  }

  public MethodMetadata configKey(String configKey) {
    this.configKey = configKey;
    return this;
  }

  public Type returnType() {
    return returnType;
  }

  public MethodMetadata returnType(Type returnType) {
    this.returnType = returnType;
    return this;
  }

  public Integer urlIndex() {
    return urlIndex;
  }

  public MethodMetadata urlIndex(Integer urlIndex) {
    this.urlIndex = urlIndex;
    return this;
  }

  public Integer bodyIndex() {
    return bodyIndex;
  }

  public MethodMetadata bodyIndex(Integer bodyIndex) {
    this.bodyIndex = bodyIndex;
    return this;
  }

  public Integer headerMapIndex() {
    return headerMapIndex;
  }

  public MethodMetadata headerMapIndex(Integer headerMapIndex) {
    this.headerMapIndex = headerMapIndex;
    return this;
  }

  public Integer queryMapIndex() {
    return queryMapIndex;
  }

  public MethodMetadata queryMapIndex(Integer queryMapIndex) {
    this.queryMapIndex = queryMapIndex;
    return this;
  }

  public boolean queryMapEncoded() {
    return queryMapEncoded;
  }

  public MethodMetadata queryMapEncoded(boolean queryMapEncoded) {
    this.queryMapEncoded = queryMapEncoded;
    return this;
  }

  /**
   * Type corresponding to {@link #bodyIndex()}.
   */
  public Type bodyType() {
    return bodyType;
  }

  public MethodMetadata bodyType(Type bodyType) {
    this.bodyType = bodyType;
    return this;
  }

  public RequestTemplate template() {
    return template;
  }

  public List<String> formParams() {
    return formParams;
  }

  public Map<Integer, Collection<String>> indexToName() {
    return indexToName;
  }

  public Map<Integer, Boolean> indexToEncoded() {
    return indexToEncoded;
  }

  /**
   * If {@link #indexToExpander} is null, classes here will be instantiated by newInstance.
   */
  public Map<Integer, Class<? extends Expander>> indexToExpanderClass() {
    return indexToExpanderClass;
  }

  /**
   * After {@link #indexToExpanderClass} is populated, this is set by contracts that support runtime
   * injection.
   */
  public MethodMetadata indexToExpander(Map<Integer, Expander> indexToExpander) {
    this.indexToExpander = indexToExpander;
    return this;
  }

  /**
   * When not null, this value will be used instead of {@link #indexToExpander()}.
   */
  public Map<Integer, Expander> indexToExpander() {
    return indexToExpander;
  }
}
