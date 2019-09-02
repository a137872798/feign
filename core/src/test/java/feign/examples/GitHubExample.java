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
package feign.examples;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.List;

import feign.*;
import feign.codec.Decoder;
import static feign.Util.ensureClosed;

/**
 * adapted from {@code com.example.retrofit.GitHubClient}
 */
public class GitHubExample {

  public static void main(String... args) {
    // target 会将 该api接口 的各个 方法属性抽取出来 并返回一个 动态代理对象 之后在调用对应的方法时 发起http 请求并返回结果
    GitHub github = Feign.builder()
        .decoder(new GsonDecoder())
        .logger(new Logger.ErrorLogger())
        .logLevel(Logger.Level.BASIC)
            // target 代表 发起的请求 和期望返回的结果类型
        .target(GitHub.class, "https://api.github.com");

    System.out.println("Let's fetch and print a list of the contributors to this library.");
    // 真正发起请求 并返回结果
    List<Contributor> contributors = github.contributors("netflix", "feign");
    for (Contributor contributor : contributors) {
      System.out.println(contributor.login + " (" + contributor.contributions + ")");
    }
  }

  /**
   * fegin demo  首先选择一个接口 该接口的方法 会被抽取成元数据 信息 之后通过某个工厂生成具备 发起http请求的client 对象
   */
  @Headers({"A:C", "B:D"})
  interface GitHub extends A{

    @RequestLine("GET /repos/{owner}/{repo}/contributors?name=123&age=11")
    List<Contributor> contributors(@Param("owner") String owner, @Param("repo") String repo);
  }

  interface A{}

  static class Contributor {

    String login;
    int contributions;
  }

  /**
   * Here's how it looks to write a decoder. Note: you can instead use {@code feign-gson}!
   * 设置解码器
   */
  static class GsonDecoder implements Decoder {

    private final Gson gson = new Gson();

    @Override
    public Object decode(Response response, Type type) throws IOException {
      if (void.class == type || response.body() == null) {
        return null;
      }
      Reader reader = response.body().asReader();
      try {
        // 将 数据体解析成需要的类型
        return gson.fromJson(reader, type);
      } catch (JsonIOException e) {
        if (e.getCause() != null && e.getCause() instanceof IOException) {
          throw IOException.class.cast(e.getCause());
        }
        throw e;
      } finally {
        ensureClosed(reader);
      }
    }
  }
}
