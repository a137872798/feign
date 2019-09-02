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

import static feign.Util.CONTENT_ENCODING;
import static feign.Util.CONTENT_LENGTH;
import static feign.Util.ENCODING_DEFLATE;
import static feign.Util.ENCODING_GZIP;
import static feign.Util.checkArgument;
import static feign.Util.checkNotNull;
import static feign.Util.isBlank;
import static feign.Util.isNotBlank;
import static java.lang.String.format;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import feign.Request.Options;

/**
 * Submits HTTP {@link Request requests}. Implementations are expected to be thread-safe.
 * 代表 发起http请求的客户端对象
 */
public interface Client {

  /**
   * Executes a request against its {@link Request#url() url} and returns a response.
   *
   * @param request safe to replay.
   * @param options options to apply to this request.
   * @return connected response, {@link Response.Body} is absent or unread.
   * @throws IOException on a network error connecting to {@link Request#url()}.
   * 发起请求并返回结果
   */
  Response execute(Request request, Options options) throws IOException;

  /**
   * Client 的默认实现
   */
  class Default implements Client {

    private final SSLSocketFactory sslContextFactory;
    private final HostnameVerifier hostnameVerifier;

    /**
     * Null parameters imply platform defaults.
     */
    public Default(SSLSocketFactory sslContextFactory, HostnameVerifier hostnameVerifier) {
      this.sslContextFactory = sslContextFactory;
      this.hostnameVerifier = hostnameVerifier;
    }

    @Override
    public Response execute(Request request, Options options) throws IOException {
      // 生成connection 并发送数据
      HttpURLConnection connection = convertAndSend(request, options);
      // 获取 res对象
      return convertResponse(connection, request);
    }

    /**
     * 解析 res 对象
     * @param connection
     * @param request
     * @return
     * @throws IOException
     */
    Response convertResponse(HttpURLConnection connection, Request request) throws IOException {
      // 获取响应结果
      int status = connection.getResponseCode();
      String reason = connection.getResponseMessage();

      if (status < 0) {
        throw new IOException(format("Invalid status(%s) executing %s %s", status,
            connection.getRequestMethod(), connection.getURL()));
      }

      // 保存res 的响应头
      Map<String, Collection<String>> headers = new LinkedHashMap<>();
      for (Map.Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
        // response message
        if (field.getKey() != null) {
          headers.put(field.getKey(), field.getValue());
        }
      }

      Integer length = connection.getContentLength();
      if (length == -1) {
        length = null;
      }
      InputStream stream;
      if (status >= 400) {
        stream = connection.getErrorStream();
      } else {
        stream = connection.getInputStream();
      }
      // 使用抽取的数据生成 res 对象
      return Response.builder()
          .status(status)
          .reason(reason)
          .headers(headers)
          .request(request)
          .body(stream, length)
          .build();
    }

    /**
     * 通过 JDK 的原生API 生成一个 Connection 对象
     * @param url
     * @return
     * @throws IOException
     */
    public HttpURLConnection getConnection(final URL url) throws IOException {
      return (HttpURLConnection) url.openConnection();
    }

    /**
     * 通过req和  optional 生成 HttpConnection 对象
     * @param request
     * @param options
     * @return
     * @throws IOException
     */
    HttpURLConnection convertAndSend(Request request, Options options) throws IOException {
      final URL url = new URL(request.url());
      // 使用url 开启 connection
      final HttpURLConnection connection = this.getConnection(url);
      if (connection instanceof HttpsURLConnection) {
        HttpsURLConnection sslCon = (HttpsURLConnection) connection;
        if (sslContextFactory != null) {
          sslCon.setSSLSocketFactory(sslContextFactory);
        }
        if (hostnameVerifier != null) {
          sslCon.setHostnameVerifier(hostnameVerifier);
        }
      }
      // 设置连接的选项
      connection.setConnectTimeout(options.connectTimeoutMillis());
      connection.setReadTimeout(options.readTimeoutMillis());
      connection.setAllowUserInteraction(false);
      connection.setInstanceFollowRedirects(options.isFollowRedirects());
      connection.setRequestMethod(request.httpMethod().name());

      // 获取编码的请求头 判断请求头是否携带压缩信息
      Collection<String> contentEncodingValues = request.headers().get(CONTENT_ENCODING);
      boolean gzipEncodedRequest =
          contentEncodingValues != null && contentEncodingValues.contains(ENCODING_GZIP);
      boolean deflateEncodedRequest =
          contentEncodingValues != null && contentEncodingValues.contains(ENCODING_DEFLATE);

      boolean hasAcceptHeader = false;
      Integer contentLength = null;
      for (String field : request.headers().keySet()) {
        if (field.equalsIgnoreCase("Accept")) {
          hasAcceptHeader = true;
        }
        for (String value : request.headers().get(field)) {
          if (field.equals(CONTENT_LENGTH)) {
            // 将长度转换类型后保存
            if (!gzipEncodedRequest && !deflateEncodedRequest) {
              contentLength = Integer.valueOf(value);
              connection.addRequestProperty(field, value);
            }
          } else {
            // 直接保存属性
            connection.addRequestProperty(field, value);
          }
        }
      }
      // Some servers choke on the default accept string.
      if (!hasAcceptHeader) {
        connection.addRequestProperty("Accept", "*/*");
      }

      if (request.requestBody().asBytes() != null) {
        // 长度设置先忽略
        if (contentLength != null) {
          connection.setFixedLengthStreamingMode(contentLength);
        } else {
          connection.setChunkedStreamingMode(8196);
        }
        connection.setDoOutput(true);
        // 该对象应该是用来写入数据的
        OutputStream out = connection.getOutputStream();
        if (gzipEncodedRequest) {
          out = new GZIPOutputStream(out);
        } else if (deflateEncodedRequest) {
          out = new DeflaterOutputStream(out);
        }
        try {
          // 是否是阻塞调用呢
          out.write(request.requestBody().asBytes());
        } finally {
          try {
            out.close();
          } catch (IOException suppressed) { // NOPMD
          }
        }
      }
      return connection;
    }
  }

  /**
   * Client that supports a {@link java.net.Proxy}.
   * Http的代理设置
   */
  class Proxied extends Default {

    public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
    private final Proxy proxy;
    /**
     * 认证信息 通过用户名密码生成
     */
    private String credentials;

    public Proxied(SSLSocketFactory sslContextFactory, HostnameVerifier hostnameVerifier,
        Proxy proxy) {
      super(sslContextFactory, hostnameVerifier);
      checkNotNull(proxy, "a proxy is required.");
      this.proxy = proxy;
    }

    public Proxied(SSLSocketFactory sslContextFactory, HostnameVerifier hostnameVerifier,
        Proxy proxy, String proxyUser, String proxyPassword) {
      this(sslContextFactory, hostnameVerifier, proxy);
      checkArgument(isNotBlank(proxyUser), "proxy user is required.");
      checkArgument(isNotBlank(proxyPassword), "proxy password is required.");
      this.credentials = basic(proxyUser, proxyPassword);
    }

    /**
     * 重写了获取 连接的方法
     * @param url
     * @return
     * @throws IOException
     */
    @Override
    public HttpURLConnection getConnection(URL url) throws IOException {
      // 使用proxy 生成connection 对象
      HttpURLConnection connection = (HttpURLConnection) url.openConnection(this.proxy);
      if (isNotBlank(this.credentials)) {
        connection.addRequestProperty(PROXY_AUTHORIZATION, this.credentials);
      }
      return connection;
    }

    public String getCredentials() {
      return this.credentials;
    }

    /**
     * 通过用户名密码 生成 某个值
     */
    private String basic(String username, String password) {
      String token = username + ":" + password;
      byte[] bytes = token.getBytes(StandardCharsets.ISO_8859_1);
      String encoded = Base64.getEncoder().encodeToString(bytes);
      return "Basic " + encoded;
    }
  }
}
