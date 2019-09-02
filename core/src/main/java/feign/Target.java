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

import static feign.Util.checkNotNull;
import static feign.Util.emptyToNull;

/**
 * <br>
 * <br>
 * <b>relationship to JAXRS 2.0</b><br>
 * <br>
 * Similar to {@code
 * javax.ws.rs.client.WebTarget}, as it produces requests. However, {@link RequestTemplate} is a
 * closer match to {@code WebTarget}.
 *
 * @param <T> type of the interface this target applies to.
 *           该对象一般是 一个接口 而且每个方法 上有对应的注解 标明了 http请求 需要的 header 和query
 */
public interface Target<T> {

  /* The type of the interface this target applies to. ex. {@code Route53}. */

  /**
   * 代表 目标对象的 类型
   * @return
   */
  Class<T> type();

  /* configuration key associated with this target. For example, {@code route53}. */

  /**
   * 对应配置的 name
   * @return
   */
  String name();

  /* base HTTP URL of the target. For example, {@code https://api/v2}. */

  /**
   * url 信息
   * @return
   */
  String url();

  /**
   * Targets a template to this target, adding the {@link #url() base url} and any target-specific
   * headers or query parameters. <br>
   * <br>
   * For example: <br>
   * 
   * <pre>
   * public Request apply(RequestTemplate input) {
   *   input.insert(0, url());
   *   input.replaceHeader(&quot;X-Auth&quot;, currentToken);
   *   return input.asRequest();
   * }
   * </pre>
   * 
   * <br>
   * <br>
   * <br>
   * <b>relationship to JAXRS 2.0</b><br>
   * <br>
   * This call is similar to {@code
   * javax.ws.rs.client.WebTarget.request()}, except that we expect transient, but necessary
   * decoration to be applied on invocation.
   * 传入 模板对象 生成req
   */
  public Request apply(RequestTemplate input);

  /**
   * 代表固定的 target 对象 属性一开始就传入了  这是最基础的一种实现 如果是基于 ribbon 的 可能会通过传入serviceName 的方式 然后在内部开启
   * 均衡负载
   * @param <T>
   */
  public static class HardCodedTarget<T> implements Target<T> {

    private final Class<T> type;
    private final String name;
    private final String url;

    /**
     * 没有设置名字时  name = url   url还包含了 请求协议 比如 http
     * @param type
     * @param url
     */
    public HardCodedTarget(Class<T> type, String url) {
      this(type, url, url);
    }

    public HardCodedTarget(Class<T> type, String name, String url) {
      this.type = checkNotNull(type, "type");
      this.name = checkNotNull(emptyToNull(name), "name");
      this.url = checkNotNull(emptyToNull(url), "url");
    }

    @Override
    public Class<T> type() {
      return type;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String url() {
      return url;
    }

    /* no authentication or other special activity. just insert the url. */

    /**
     * 通过模板对象生成 req
     * @param input
     * @return
     */
    @Override
    public Request apply(RequestTemplate input) {
      // 如果没有携带 http 信息 需要 获取 target 的 url 信息并追加到头部
      if (input.url().indexOf("http") != 0) {
        input.target(url());
      }
      // 直接返回 模板的 req 对象
      return input.request();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof HardCodedTarget) {
        HardCodedTarget<?> other = (HardCodedTarget) obj;
        return type.equals(other.type)
            && name.equals(other.name)
            && url.equals(other.url);
      }
      return false;
    }

    @Override
    public int hashCode() {
      int result = 17;
      result = 31 * result + type.hashCode();
      result = 31 * result + name.hashCode();
      result = 31 * result + url.hashCode();
      return result;
    }

    @Override
    public String toString() {
      if (name.equals(url)) {
        return "HardCodedTarget(type=" + type.getSimpleName() + ", url=" + url + ")";
      }
      return "HardCodedTarget(type=" + type.getSimpleName() + ", name=" + name + ", url=" + url
          + ")";
    }
  }

  /**
   * 空对象
   * @param <T>
   */
  public static final class EmptyTarget<T> implements Target<T> {

    private final Class<T> type;
    private final String name;

    EmptyTarget(Class<T> type, String name) {
      this.type = checkNotNull(type, "type");
      this.name = checkNotNull(emptyToNull(name), "name");
    }

    public static <T> EmptyTarget<T> create(Class<T> type) {
      return new EmptyTarget<T>(type, "empty:" + type.getSimpleName());
    }

    public static <T> EmptyTarget<T> create(Class<T> type, String name) {
      return new EmptyTarget<T>(type, name);
    }

    @Override
    public Class<T> type() {
      return type;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String url() {
      throw new UnsupportedOperationException("Empty targets don't have URLs");
    }

    @Override
    public Request apply(RequestTemplate input) {
      if (input.url().indexOf("http") != 0) {
        throw new UnsupportedOperationException(
            "Request with non-absolute URL not supported with empty target");
      }
      return input.request();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof EmptyTarget) {
        EmptyTarget<?> other = (EmptyTarget) obj;
        return type.equals(other.type)
            && name.equals(other.name);
      }
      return false;
    }

    @Override
    public int hashCode() {
      int result = 17;
      result = 31 * result + type.hashCode();
      result = 31 * result + name.hashCode();
      return result;
    }

    @Override
    public String toString() {
      if (name.equals("empty:" + type.getSimpleName())) {
        return "EmptyTarget(type=" + type.getSimpleName() + ")";
      }
      return "EmptyTarget(type=" + type.getSimpleName() + ", name=" + name + ")";
    }
  }
}
