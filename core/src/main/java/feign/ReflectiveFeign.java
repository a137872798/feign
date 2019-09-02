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

import feign.template.UriUtils;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.Map.Entry;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Param.Expander;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;

/**
 * 核心类
 */
public class ReflectiveFeign extends Feign {

  private final ParseHandlersByName targetToHandlersByName;
  private final InvocationHandlerFactory factory;
  private final QueryMapEncoder queryMapEncoder;

  ReflectiveFeign(ParseHandlersByName targetToHandlersByName, InvocationHandlerFactory factory,
      QueryMapEncoder queryMapEncoder) {
    this.targetToHandlersByName = targetToHandlersByName;
    this.factory = factory;
    this.queryMapEncoder = queryMapEncoder;
  }

  /**
   * creates an api binding to the {@code target}. As this invokes reflection, care should be taken
   * to cache the result.
   * 处理过 target 后 返回
   */
  @SuppressWarnings("unchecked")
  @Override
  public <T> T newInstance(Target<T> target) {
    // 处理目标对象并将 方法名和 对应的方法处理器 映射起来
    Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
    Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
    List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();

    for (Method method : target.type().getMethods()) {
      if (method.getDeclaringClass() == Object.class) {
        continue;
      } else if (Util.isDefault(method)) {
        DefaultMethodHandler handler = new DefaultMethodHandler(method);
        defaultMethodHandlers.add(handler);
        methodToHandler.put(method, handler);
      } else {
        methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
      }
    }
    InvocationHandler handler = factory.create(target, methodToHandler);
    T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(),
        new Class<?>[] {target.type()}, handler);

    for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
      defaultMethodHandler.bindTo(proxy);
    }
    return proxy;
  }

  /**
   * 特殊的handler 对象
   */
  static class FeignInvocationHandler implements InvocationHandler {

    private final Target target;
    private final Map<Method, MethodHandler> dispatch;

    FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
      this.target = checkNotNull(target, "target");
      this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("equals".equals(method.getName())) {
        try {
          Object otherHandler =
              args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
          return equals(otherHandler);
        } catch (IllegalArgumentException e) {
          return false;
        }
      } else if ("hashCode".equals(method.getName())) {
        return hashCode();
      } else if ("toString".equals(method.getName())) {
        return toString();
      }

      // 除基本方法外 通过方法 映射到对应的处理器
      return dispatch.get(method).invoke(args);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof FeignInvocationHandler) {
        FeignInvocationHandler other = (FeignInvocationHandler) obj;
        return target.equals(other.target);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return target.hashCode();
    }

    @Override
    public String toString() {
      return target.toString();
    }
  }

  /**
   * 通过name 属性来解析 handler
   */
  static final class ParseHandlersByName {

    /**
     * 合同对象
     */
    private final Contract contract;
    /**
     * 选项
     */
    private final Options options;
    private final Encoder encoder;
    private final Decoder decoder;
    private final ErrorDecoder errorDecoder;
    private final QueryMapEncoder queryMapEncoder;
    private final SynchronousMethodHandler.Factory factory;

    ParseHandlersByName(
        Contract contract,
        Options options,
        Encoder encoder,
        Decoder decoder,
        QueryMapEncoder queryMapEncoder,
        ErrorDecoder errorDecoder,
        SynchronousMethodHandler.Factory factory) {
      this.contract = contract;
      this.options = options;
      this.factory = factory;
      this.errorDecoder = errorDecoder;
      this.queryMapEncoder = queryMapEncoder;
      this.encoder = checkNotNull(encoder, "encoder");
      this.decoder = checkNotNull(decoder, "decoder");
    }

    /**
     * 将目标对象的 方法 处理器抽取出来
     * @param key
     * @return
     */
    public Map<String, MethodHandler> apply(Target key) {
      // 抽取该class 的 方法元数据信息
      List<MethodMetadata> metadata = contract.parseAndValidatateMetadata(key.type());
      Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
      for (MethodMetadata md : metadata) {
        BuildTemplateByResolvingArgs buildTemplate;
        // formParam 代表 该方法的参数携带@Param 注解
        if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
          buildTemplate = new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder);
        // 如果存在 body 的下标
        } else if (md.bodyIndex() != null) {
          buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder);
        } else {
          // 默认情况
          buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder);
        }
        // config 作为唯一确定method 的标识 通过工厂对象 构建 methodHandler
        result.put(md.configKey(),
            factory.create(key, md, buildTemplate, options, decoder, errorDecoder));
      }
      return result;
    }
  }

  /**
   * 根据给定的参数 构成一个 reqTemplate 对象
   */
  private static class BuildTemplateByResolvingArgs implements RequestTemplate.Factory {

    /**
     * 该对象具备将一个 Object 的属性抽取出来生成一个 map
     */
    private final QueryMapEncoder queryMapEncoder;

    /**
     * 包含对象的 方法元数据信息
     */
    protected final MethodMetadata metadata;
    /**
     * 维护 参数下标与 拓展对象
     */
    private final Map<Integer, Expander> indexToExpander = new LinkedHashMap<Integer, Expander>();

    /**
     * 通过传入的参数进行初始化
     * @param metadata
     * @param queryMapEncoder
     */
    private BuildTemplateByResolvingArgs(MethodMetadata metadata, QueryMapEncoder queryMapEncoder) {
      this.metadata = metadata;
      this.queryMapEncoder = queryMapEncoder;
      if (metadata.indexToExpander() != null) {
        indexToExpander.putAll(metadata.indexToExpander());
        return;
      }
      // 对应的 拓展 类如果不存在 直接返回
      if (metadata.indexToExpanderClass().isEmpty()) {
        return;
      }
      for (Entry<Integer, Class<? extends Expander>> indexToExpanderClass : metadata
          .indexToExpanderClass().entrySet()) {
        try {
          indexToExpander
              .put(indexToExpanderClass.getKey(), indexToExpanderClass.getValue().newInstance());
        } catch (InstantiationException e) {
          throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    /**
     * 通过一组参数对象  构建 reqTemplate 对象  看来就是调用某个方法的参数 来生成RequestTemplate
     * @param argv
     * @return
     */
    @Override
    public RequestTemplate create(Object[] argv) {
      // 默认是一个空对象
      RequestTemplate mutable = RequestTemplate.from(metadata.template());
      // 如果参数中 有 URI 类型
      if (metadata.urlIndex() != null) {
        int urlIndex = metadata.urlIndex();
        checkArgument(argv[urlIndex] != null, "URI parameter %s was null", urlIndex);
        // 使用 uri 参数进行初始化
        mutable.target(String.valueOf(argv[urlIndex]));
      }
      Map<String, Object> varBuilder = new LinkedHashMap<String, Object>();
      // 维护 携带@Param 参数的 容器 key 代表下标 value 代表 @Param 的name
      for (Entry<Integer, Collection<String>> entry : metadata.indexToName().entrySet()) {
        int i = entry.getKey();
        Object value = argv[entry.getKey()];
        if (value != null) { // Null values are skipped.
          if (indexToExpander.containsKey(i)) {
            // 使用 Expander 和 参数 进行拓展
            value = expandElements(indexToExpander.get(i), value);
          }
          for (String name : entry.getValue()) {
            varBuilder.put(name, value);
          }
        }
      }

      // 使用解析出来的参数 完善 requestTemplate 对象
      RequestTemplate template = resolve(argv, mutable, varBuilder);
      if (metadata.queryMapIndex() != null) {
        // add query map parameters after initial resolve so that they take
        // precedence over any predefined values
        // 代表该 参数 是携带了 @QueryMap (需要设置到uri中)  且 Key 必须是 String 类型
        Object value = argv[metadata.queryMapIndex()];
        Map<String, Object> queryMap = toQueryMap(value);
        // 设置query 参数
        template = addQueryMapQueryParameters(queryMap, template);
      }

      if (metadata.headerMapIndex() != null) {
        // 设置请求头参数  因为 在生成 metadata时 就要求 该参数类型必须是 Map
        template =
            addHeaderMapHeaders((Map<String, Object>) argv[metadata.headerMapIndex()], template);
      }

      return template;
    }

    private Map<String, Object> toQueryMap(Object value) {
      if (value instanceof Map) {
        return (Map<String, Object>) value;
      }
      try {
        return queryMapEncoder.encode(value);
      } catch (EncodeException e) {
        throw new IllegalStateException(e);
      }
    }

    /**
     * 使用 expander 拓展参数
     * @param expander
     * @param value
     * @return
     */
    private Object expandElements(Expander expander, Object value) {
      if (value instanceof Iterable) {
        return expandIterable(expander, (Iterable) value);
      }
      return expander.expand(value);
    }

    private List<String> expandIterable(Expander expander, Iterable value) {
      List<String> values = new ArrayList<String>();
      for (Object element : value) {
        if (element != null) {
          // 迭代 拓展
          values.add(expander.expand(element));
        }
      }
      return values;
    }

    /**
     * 从方法列表中寻找携带 @HeaderMap 的参数 并获取数据填充到 requestTemplate中
     * @param headerMap
     * @param mutable
     * @return
     */
    @SuppressWarnings("unchecked")
    private RequestTemplate addHeaderMapHeaders(Map<String, Object> headerMap,
                                                RequestTemplate mutable) {
      for (Entry<String, Object> currEntry : headerMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null : nextObject.toString());
          }
        } else {
          values.add(currValue == null ? null : currValue.toString());
        }

        mutable.header(currEntry.getKey(), values);
      }
      return mutable;
    }

    /**
     * 将queryMap 属性设置到 requestTemplate 中
     * @param queryMap
     * @param mutable
     * @return
     */
    @SuppressWarnings("unchecked")
    private RequestTemplate addQueryMapQueryParameters(Map<String, Object> queryMap,
                                                       RequestTemplate mutable) {
      for (Entry<String, Object> currEntry : queryMap.entrySet()) {
        Collection<String> values = new ArrayList<String>();

        // 是否已经编码
        boolean encoded = metadata.queryMapEncoded();
        Object currValue = currEntry.getValue();
        if (currValue instanceof Iterable<?>) {
          Iterator<?> iter = ((Iterable<?>) currValue).iterator();
          while (iter.hasNext()) {
            Object nextObject = iter.next();
            values.add(nextObject == null ? null
                : encoded ? nextObject.toString()
                    // 将参数 进行编码
                    : UriUtils.encode(nextObject.toString()));
          }
        } else {
          values.add(currValue == null ? null
              : encoded ? currValue.toString() : UriUtils.encode(currValue.toString()));
        }

        // 将 key 编码后 填充到 requestTemplate 中
        mutable.query(encoded ? currEntry.getKey() : UriUtils.encode(currEntry.getKey()), values);
      }
      return mutable;
    }

    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      return mutable.resolve(variables);
    }
  }

  /**
   * 代表 从携带 @Param的参数中获取 所需信息生成 requestTemplate
   */
  private static class BuildFormEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    /**
     * 编码对象 默认实现基本是不做处理  比如 基于 gson的实现 就会将 参数 转换成json 格式
     */
    private final Encoder encoder;

    private BuildFormEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder) {
      super(metadata, queryMapEncoder);
      this.encoder = encoder;
    }

    /**
     * 增强了  通过指定参数生成requestTemplate 的方法， 默认实现是直接 调用requestTemplate.resolve();
     * @param argv
     * @param mutable
     * @param variables
     * @return
     */
    @Override
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      Map<String, Object> formVariables = new LinkedHashMap<String, Object>();
      for (Entry<String, Object> entry : variables.entrySet()) {
        // formParams 代表 该方法中所有携带 @Param 参数的 name 属性  这里只有 已经存在key了 才会添加属性 同时variables 本身就是从 formParams 中获取的
        if (metadata.formParams().contains(entry.getKey())) {
          formVariables.put(entry.getKey(), entry.getValue());
        }
      }
      try {
        // 使用指定参数 协助 编码
        encoder.encode(formVariables, Encoder.MAP_STRING_WILDCARD, mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }

  /**
   * 基于 body 属性进行初始化
   */
  private static class BuildEncodedTemplateFromArgs extends BuildTemplateByResolvingArgs {

    private final Encoder encoder;

    private BuildEncodedTemplateFromArgs(MethodMetadata metadata, Encoder encoder,
        QueryMapEncoder queryMapEncoder) {
      super(metadata, queryMapEncoder);
      this.encoder = encoder;
    }

    @Override
    protected RequestTemplate resolve(Object[] argv,
                                      RequestTemplate mutable,
                                      Map<String, Object> variables) {
      // 获取 body 信息
      Object body = argv[metadata.bodyIndex()];
      checkArgument(body != null, "Body parameter %s was null", metadata.bodyIndex());
      try {
        encoder.encode(body, metadata.bodyType(), mutable);
      } catch (EncodeException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EncodeException(e.getMessage(), e);
      }
      return super.resolve(argv, mutable, variables);
    }
  }
}
