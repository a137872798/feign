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
package feign.ribbon;

import com.netflix.client.AbstractLoadBalancerAwareClient;
import com.netflix.client.ClientException;
import com.netflix.client.ClientRequest;
import com.netflix.client.IResponse;
import com.netflix.client.RequestSpecificRetryHandler;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;
import feign.Request.HttpMethod;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import feign.Client;
import feign.Request;
import feign.Response;
import feign.Util;

/**
 * 继承自 ribbon 的 AbstractLBAwareClient 具备 均衡负载能力
 */
public final class LBClient extends
    AbstractLoadBalancerAwareClient<LBClient.RibbonRequest, LBClient.RibbonResponse> {

  /**
   * 连接超时时间
   */
  private final int connectTimeout;
  /**
   * 读取超时时间
   */
  private final int readTimeout;
  /**
   * client 配置
   */
  private final IClientConfig clientConfig;
  /**
   * 允许重试的状态码
   */
  private final Set<Integer> retryableStatusCodes;
  /**
   * 是否允许重定向
   */
  private final Boolean followRedirects;

  public static LBClient create(ILoadBalancer lb, IClientConfig clientConfig) {
    return new LBClient(lb, clientConfig);
  }

  /**
   * 通过传入一个 字符串 拆分成 一组 异常code
   * @param statusCodesString
   * @return
   */
  static Set<Integer> parseStatusCodes(String statusCodesString) {
    if (statusCodesString == null || statusCodesString.isEmpty()) {
      return Collections.emptySet();
    }
    Set<Integer> codes = new LinkedHashSet<Integer>();
    for (String codeString : statusCodesString.split(",")) {
      codes.add(Integer.parseInt(codeString));
    }
    return codes;
  }

  /**
   * 初始化
   * @param lb
   * @param clientConfig
   */
  LBClient(ILoadBalancer lb, IClientConfig clientConfig) {
    super(lb, clientConfig);
    this.clientConfig = clientConfig;

    // 下面4种属性都是 直接从配置工厂中 获取 如果获取不到会使用默认值
    connectTimeout = clientConfig.get(CommonClientConfigKey.ConnectTimeout);
    readTimeout = clientConfig.get(CommonClientConfigKey.ReadTimeout);
    retryableStatusCodes = parseStatusCodes(clientConfig.get(LBClientFactory.RetryableStatusCodes));
    followRedirects = clientConfig.get(CommonClientConfigKey.FollowRedirects);
  }

  /**
   * 传入的 IClientConfig 应该是针对某次调用传入 覆盖的属性
   * 在 ribbon 中 一般是通过调用 client 的 executeWithLoadBalancer 方法 对 serverList 均衡负载选择其中一个后 在 调用execute 生成结果
   * 这里就是 替换掉了这个部分
   * @param request
   * @param configOverride
   * @return
   * @throws IOException
   * @throws ClientException
   */
  @Override
  public RibbonResponse execute(RibbonRequest request, IClientConfig configOverride)
      throws IOException, ClientException {
    Request.Options options;
    if (configOverride != null) {
      options =
          new Request.Options(
              configOverride.get(CommonClientConfigKey.ConnectTimeout, connectTimeout),
              (configOverride.get(CommonClientConfigKey.ReadTimeout, readTimeout)),
              configOverride.get(CommonClientConfigKey.FollowRedirects, followRedirects));
    } else {
      options = new Request.Options(connectTimeout, readTimeout);
    }
    // 通过 feign的 client 对象发起请求
    Response response = request.client().execute(request.toRequest(), options);
    // 如果可重试 这里抛出异常会在 rxjava.retry 中被捕获 从而进行重试
    if (retryableStatusCodes.contains(response.status())) {
      response.close();
      throw new ClientException(ClientException.ErrorType.SERVER_THROTTLED);
    }
    return new RibbonResponse(request.getUri(), response);
  }

  /**
   * 该函数作用是 当 ribbon.execute() 遇到异常时 是否进行重试
   * @param request
   * @param requestConfig
   * @return
   */
  @Override
  public RequestSpecificRetryHandler getRequestSpecificRetryHandler(
                                                                    RibbonRequest request,
                                                                    IClientConfig requestConfig) {
    if (clientConfig.get(CommonClientConfigKey.OkToRetryOnAllOperations, false)) {
      return new RequestSpecificRetryHandler(true, true, this.getRetryHandler(), requestConfig);
    }
    if (request.toRequest().httpMethod() != HttpMethod.GET) {
      return new RequestSpecificRetryHandler(true, false, this.getRetryHandler(), requestConfig);
    } else {
      return new RequestSpecificRetryHandler(true, true, this.getRetryHandler(), requestConfig);
    }
  }


  static class RibbonRequest extends ClientRequest implements Cloneable {

    /**
     * 内部维护了 feign的req 对象
     */
    private final Request request;
    /**
     * feign 的 client 对象
     */
    private final Client client;

    RibbonRequest(Client client, Request request, URI uri) {
      this.client = client;
      this.request = request;
      setUri(uri);
    }

    Request toRequest() {
      // add header "Content-Length" according to the request body
      final byte[] body = request.requestBody().asBytes();
      final int bodyLength = body != null ? body.length : 0;
      // create a new Map to avoid side effect, not to change the old headers
      Map<String, Collection<String>> headers = new LinkedHashMap<String, Collection<String>>();
      headers.putAll(request.headers());
      headers.put(Util.CONTENT_LENGTH, Collections.singletonList(String.valueOf(bodyLength)));
      return Request.create(request.httpMethod(), getUri().toASCIIString(), headers, body,
          request.charset());
    }

    Client client() {
      return client;
    }

    public Object clone() {
      return new RibbonRequest(client, request, getUri());
    }
  }

  static class RibbonResponse implements IResponse {

    private final URI uri;
    private final Response response;

    RibbonResponse(URI uri, Response response) {
      this.uri = uri;
      this.response = response;
    }

    @Override
    public Object getPayload() throws ClientException {
      return response.body();
    }

    @Override
    public boolean hasPayload() {
      return response.body() != null;
    }

    @Override
    public boolean isSuccess() {
      return response.status() == 200;
    }

    @Override
    public URI getRequestedURI() {
      return uri;
    }

    @Override
    public Map<String, Collection<String>> getHeaders() {
      return response.headers();
    }

    Response toResponse() {
      return response;
    }

    @Override
    public void close() throws IOException {
      if (response != null && response.body() != null) {
        response.body().close();
      }
    }

  }

}
