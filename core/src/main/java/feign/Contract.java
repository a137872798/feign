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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import feign.Request.HttpMethod;
import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * Defines what annotations and values are valid on interfaces.
 * 合同接口
 */
public interface Contract {

  /**
   * Called to parse the methods in the class that are linked to HTTP requests.
   *
   * @param targetType {@link feign.Target#type() type} of the Feign interface.
   */
  // TODO: break this and correct spelling at some point
  List<MethodMetadata> parseAndValidatateMetadata(Class<?> targetType);

  /**
   * 合同基类
   */
  abstract class BaseContract implements Contract {

    /**
     * 解析 目标类并生成一组方法元数据
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     * @return
     */
    @Override
    public List<MethodMetadata> parseAndValidatateMetadata(Class<?> targetType) {
      // 不能包含泛型参数
      checkState(targetType.getTypeParameters().length == 0, "Parameterized types unsupported: %s",
          targetType.getSimpleName());
      // 至多一个接口
      checkState(targetType.getInterfaces().length <= 1, "Only single inheritance supported: %s",
          targetType.getSimpleName());
      if (targetType.getInterfaces().length == 1) {
        // 接口上层不允许存在其他接口
        checkState(targetType.getInterfaces()[0].getInterfaces().length == 0,
            "Only single-level inheritance supported: %s",
            targetType.getSimpleName());
      }
      Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();
      for (Method method : targetType.getMethods()) {
        // 不处理 Object 的方法 和静态方法
        if (method.getDeclaringClass() == Object.class ||
            (method.getModifiers() & Modifier.STATIC) != 0 ||
            Util.isDefault(method)) {
          continue;
        }
        // 将对应方法 的 元数据信息抽取出来
        MethodMetadata metadata = parseAndValidateMetadata(targetType, method);
        // 不允许出现 重写接口 比如 父接口 和子接口有相同的方法
        checkState(!result.containsKey(metadata.configKey()), "Overrides unsupported: %s",
            metadata.configKey());
        result.put(metadata.configKey(), metadata);
      }
      return new ArrayList<>(result.values());
    }

    /**
     * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
     */
    @Deprecated
    public MethodMetadata parseAndValidatateMetadata(Method method) {
      return parseAndValidateMetadata(method.getDeclaringClass(), method);
    }

    /**
     * Called indirectly by {@link #parseAndValidatateMetadata(Class)}.
     * 解析某个方法并生成元数据对象
     */
    protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
      MethodMetadata data = new MethodMetadata();
      // 设置返回类型  传入的是一个 api接口 然后每个 方法 的返回类型信息都会保存 之后 生成一个代理对象 之后可以指定调用 某个方法 返回对应的结果
      data.returnType(Types.resolve(targetType, targetType, method.getGenericReturnType()));
      // configKey 看来是 一个class+method 的唯一标识
      data.configKey(Feign.configKey(targetType, method));

      // 尝试从目标类获取 Header 注解信息 并将数据转换成 请求头信息 填充到data 中 如果存在上级接口 就将 请求头数据 整合起来
      if (targetType.getInterfaces().length == 1) {
        processAnnotationOnClass(data, targetType.getInterfaces()[0]);
      }
      processAnnotationOnClass(data, targetType);


      // 获取 方法上的 注解
      for (Annotation methodAnnotation : method.getAnnotations()) {
        processAnnotationOnMethod(data, methodAnnotation, method);
      }
      checkState(data.template().method() != null,
          "Method %s not annotated with HTTP method type (ex. GET, POST)",
          method.getName());
      // 获取该方法的请求参数
      Class<?>[] parameterTypes = method.getParameterTypes();
      // 获取该方法的请求参数
      Type[] genericParameterTypes = method.getGenericParameterTypes();

      // 获取参数的 注解信息
      Annotation[][] parameterAnnotations = method.getParameterAnnotations();
      int count = parameterAnnotations.length;
      for (int i = 0; i < count; i++) {
        // 判断参数上是否有 Http相关参数
        boolean isHttpAnnotation = false;
        if (parameterAnnotations[i] != null) {
          isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
        }
        // 如果参数类型是 URL
        if (parameterTypes[i] == URI.class) {
          data.urlIndex(i);
        // 代表该参数没有携带 Http 相关的注解 且类型不是 Options  一般就是 实体类型
        } else if (!isHttpAnnotation && parameterTypes[i] != Request.Options.class) {
          // 这种情况 必须确保首次进入这里 之前的参数都不包含 @Param 注解
          checkState(data.formParams().isEmpty(),
              "Body parameters cannot be used with form parameters.");
          // 一个方法列表中只能存在一个 实体对象
          checkState(data.bodyIndex() == null, "Method has too many Body parameters: %s", method);
          // 设置 body 类型
          data.bodyIndex(i);
          data.bodyType(Types.resolve(targetType, targetType, genericParameterTypes[i]));
        }
      }

      if (data.headerMapIndex() != null) {
        // 要求携带 @HeaderMap 的参数类型必须是 Map类型
        checkMapString("HeaderMap", parameterTypes[data.headerMapIndex()],
            // 要求 该参数的泛型信息必须是 String 类型 如果没有泛型信息就不处理
            genericParameterTypes[data.headerMapIndex()]);
      }

      if (data.queryMapIndex() != null) {
        // 携带@QueryMap 的参数 必须是 Map类型 且泛型必须是String 类型
        if (Map.class.isAssignableFrom(parameterTypes[data.queryMapIndex()])) {
          checkMapKeys("QueryMap", genericParameterTypes[data.queryMapIndex()]);
        }
      }

      return data;
    }

    private static void checkMapString(String name, Class<?> type, Type genericType) {
      checkState(Map.class.isAssignableFrom(type),
          "%s parameter must be a Map: %s", name, type);
      checkMapKeys(name, genericType);
    }

    /**
     * 校验 map 的key
     * @param name 指定 key值
     * @param genericType
     */
    private static void checkMapKeys(String name, Type genericType) {
      Class<?> keyClass = null;

      // assume our type parameterized
      // 如果是泛型参数
      if (ParameterizedType.class.isAssignableFrom(genericType.getClass())) {
        // 获取 泛型的 第一个参数
        Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
        keyClass = (Class<?>) parameterTypes[0];
      } else if (genericType instanceof Class<?>) {
        // raw class, type parameters cannot be inferred directly, but we can scan any extended
        // interfaces looking for any explict types
        // 返回目标类实现的所有接口
        Type[] interfaces = ((Class) genericType).getGenericInterfaces();
        if (interfaces != null) {
          for (Type extended : interfaces) {
            // 如果接口级别 是泛型相关的
            if (ParameterizedType.class.isAssignableFrom(extended.getClass())) {
              // use the first extended interface we find.
              // 获取第一个泛型类型
              Type[] parameterTypes = ((ParameterizedType) extended).getActualTypeArguments();
              keyClass = (Class<?>) parameterTypes[0];
              break;
            }
          }
        }
      }

      // 要求泛型必须是 String 类型
      if (keyClass != null) {
        checkState(String.class.equals(keyClass),
            "%s key must be a String: %s", name, keyClass.getSimpleName());
      }
    }


    /**
     * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target
     * type (unless they are the same).
     *
     * @param data metadata collected so far relating to the current java method.
     * @param clz the class to process
     */
    protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotation annotations present on the current method annotation.
     * @param method method currently being processed.
     */
    protected abstract void processAnnotationOnMethod(MethodMetadata data,
                                                      Annotation annotation,
                                                      Method method);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex if you find a name in {@code annotations}, call
     *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
     *         http-relevant annotation.
     */
    protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                             Annotation[] annotations,
                                                             int paramIndex);

    /**
     * links a parameter name to its index in the method signature.
     * 将参数信息 添加到 data中
     */
    protected void nameParam(MethodMetadata data, String name, int i) {
      Collection<String> names =
          data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
      names.add(name);
      data.indexToName().put(i, names);
    }
  }

  class Default extends BaseContract {

    static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+)[ ]*(.*)$");

    /**
     * 读取注解信息
     * @param data metadata collected so far relating to the current java method.  待设置的元数据对象
     * @param targetType  目标class
     */
    @Override
    protected void processAnnotationOnClass(MethodMetadata data, Class<?> targetType) {
      // 如果api接口类 携带 Header注解
      if (targetType.isAnnotationPresent(Headers.class)) {
        String[] headersOnType = targetType.getAnnotation(Headers.class).value();
        checkState(headersOnType.length > 0, "Headers annotation was empty on type %s.",
            targetType.getName());
        // 将请求头信息转换成 map 对象  请求头的格式 是 x:y
        Map<String, Collection<String>> headers = toMap(headersOnType);
        headers.putAll(data.template().headers());
        // 更新header 对象  会生成对应的 HeaderTemplate 对象
        data.template().headers(null); // to clear
        data.template().headers(headers);
      }
    }

    /**
     * 获取方法级别的注解信息并填充到 元数据中
     * @param data metadata collected so far relating to the current java method.
     * @param methodAnnotation    修饰方法的注解
     * @param method method currently being processed.
     */
    @Override
    protected void processAnnotationOnMethod(MethodMetadata data,
                                             Annotation methodAnnotation,
                                             Method method) {
      // 获取注解信息
      Class<? extends Annotation> annotationType = methodAnnotation.annotationType();
      // 如果是请求行注解
      if (annotationType == RequestLine.class) {
        // 获取注解数据
        String requestLine = RequestLine.class.cast(methodAnnotation).value();
        checkState(emptyToNull(requestLine) != null,
            "RequestLine annotation was empty on method %s.", method.getName());

        // 填入的数据必须满足某种格式  比如 GET /repos/{owner}/{repo}/contributors
        Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
        if (!requestLineMatcher.find()) {
          throw new IllegalStateException(String.format(
              "RequestLine annotation didn't start with an HTTP verb on method %s",
              method.getName()));
        } else {
          // 匹配的 第一个元素是 请求方式
          data.template().method(HttpMethod.valueOf(requestLineMatcher.group(1)));
          // 匹配的 第二个元素是 url  在设置url 的时候 对应会初始化 UrlTemplate
          data.template().uri(requestLineMatcher.group(2));
        }
        // decodeSlash 代表 是否要解析 斜线  默认为true
        data.template().decodeSlash(RequestLine.class.cast(methodAnnotation).decodeSlash());
        // 设置 collection 参数的分隔符
        data.template()
            .collectionFormat(RequestLine.class.cast(methodAnnotation).collectionFormat());

      // 如果参数是 Body 类型  比如     @Body("%7B\"login\":\"{login}\",\"type\":\"{type}\"%7D")  %7B %7D 代表是 JSON 格式
      } else if (annotationType == Body.class) {
        String body = Body.class.cast(methodAnnotation).value();
        checkState(emptyToNull(body) != null, "Body annotation was empty on method %s.",
            method.getName());
        if (body.indexOf('{') == -1) {
          data.template().body(body);
        } else {
          // 设置 body  (body 内部有一个 bodyTemplate 属性)
          data.template().bodyTemplate(body);
        }
      // 如果是 Headers 注解
      } else if (annotationType == Headers.class) {
        String[] headersOnMethod = Headers.class.cast(methodAnnotation).value();
        checkState(headersOnMethod.length > 0, "Headers annotation was empty on method %s.",
            method.getName());
        data.template().headers(toMap(headersOnMethod));
      }
    }

    /**
     * 从方法中某个参数上 解析注解信息
     * @param data metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex if you find a name in {@code annotations}, call
     *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.  代表解析的该参数对于该方法参数列表的下标
     * @return
     */
    @Override
    protected boolean processAnnotationsOnParameter(MethodMetadata data,
                                                    Annotation[] annotations,
                                                    int paramIndex) {
      boolean isHttpAnnotation = false;
      for (Annotation annotation : annotations) {
        // 获取该参数的注解类型
        Class<? extends Annotation> annotationType = annotation.annotationType();
        // 如果是 Param
        if (annotationType == Param.class) {
          Param paramAnnotation = (Param) annotation;
          String name = paramAnnotation.value();
          checkState(emptyToNull(name) != null, "Param annotation was empty on param %s.",
              paramIndex);
          // 设置 name 与 index 的关系到 data中 (indexToName)
          nameParam(data, name, paramIndex);
          // 尝试获取参数的拓展信息
          Class<? extends Param.Expander> expander = paramAnnotation.expander();
          if (expander != Param.ToStringExpander.class) {
            data.indexToExpanderClass().put(paramIndex, expander);
          }
          data.indexToEncoded().put(paramIndex, paramAnnotation.encoded());
          // 代表该参数携带了 Http 的相关注解
          isHttpAnnotation = true;
          // 目前 template 还没有包含指定参数时
          if (!data.template().hasRequestVariable(name)) {
            data.formParams().add(name);
          }
        // 如果携带的注解是 QueryMap
        } else if (annotationType == QueryMap.class) {
          // 看来 只能有一个参数 携带该注解
          checkState(data.queryMapIndex() == null,
              "QueryMap annotation was present on multiple parameters.");
          data.queryMapIndex(paramIndex);
          data.queryMapEncoded(QueryMap.class.cast(annotation).encoded());
          isHttpAnnotation = true;
        // 如果携带的注解是 HeaderMap
        } else if (annotationType == HeaderMap.class) {
          checkState(data.headerMapIndex() == null,
              "HeaderMap annotation was present on multiple parameters.");
          data.headerMapIndex(paramIndex);
          isHttpAnnotation = true;
        }
      }
      return isHttpAnnotation;
    }

    /**
     * 将一个数组转换成 请求头数据
     * @param input
     * @return
     */
    private static Map<String, Collection<String>> toMap(String[] input) {
      Map<String, Collection<String>> result =
          new LinkedHashMap<String, Collection<String>>(input.length);
      // input 中每个数据都是通过 : 拼接成的
      for (String header : input) {
        int colon = header.indexOf(':');
        // 代表 header 的 name
        String name = header.substring(0, colon);
        if (!result.containsKey(name)) {
          result.put(name, new ArrayList<String>(1));
        }
        // 将数据填充到 list中
        result.get(name).add(header.substring(colon + 1).trim());
      }
      return result;
    }
  }
}
