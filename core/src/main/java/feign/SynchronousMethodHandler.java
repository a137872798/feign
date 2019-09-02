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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import static feign.ExceptionPropagationPolicy.UNWRAP;
import static feign.FeignException.errorExecuting;
import static feign.FeignException.errorReading;
import static feign.Util.checkNotNull;
import static feign.Util.ensureClosed;

/**
 * 同步方法处理器
 */
final class SynchronousMethodHandler implements MethodHandler {

  private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;

  /**
   * 方法元数据
   */
  private final MethodMetadata metadata;
  /**
   * 包含请求信息的类
   */
  private final Target<?> target;
  /**
   * 该对象能将 req 加工成 connection
   */
  private final Client client;
  /**
   * 重试策略
   */
  private final Retryer retryer;
  /**
   * 请求拦截器
   */
  private final List<RequestInterceptor> requestInterceptors;
  private final Logger logger;
  private final Logger.Level logLevel;
  /**
   * 请求模板工厂  具备将参数生成 reqTemplate 的能力
   */
  private final RequestTemplate.Factory buildTemplateFromArgs;
  private final Options options;
  private final Decoder decoder;
  private final ErrorDecoder errorDecoder;
  private final boolean decode404;
  private final boolean closeAfterDecode;
  private final ExceptionPropagationPolicy propagationPolicy;

  /**
   * 初始化只是简单的赋值操作
   * @param target
   * @param client
   * @param retryer
   * @param requestInterceptors
   * @param logger
   * @param logLevel
   * @param metadata
   * @param buildTemplateFromArgs
   * @param options
   * @param decoder
   * @param errorDecoder
   * @param decode404
   * @param closeAfterDecode
   * @param propagationPolicy
   */
  private SynchronousMethodHandler(Target<?> target, Client client, Retryer retryer,
      List<RequestInterceptor> requestInterceptors, Logger logger,
      Logger.Level logLevel, MethodMetadata metadata,
      RequestTemplate.Factory buildTemplateFromArgs, Options options,
      Decoder decoder, ErrorDecoder errorDecoder, boolean decode404,
      boolean closeAfterDecode, ExceptionPropagationPolicy propagationPolicy) {
    this.target = checkNotNull(target, "target");
    this.client = checkNotNull(client, "client for %s", target);
    this.retryer = checkNotNull(retryer, "retryer for %s", target);
    this.requestInterceptors =
        checkNotNull(requestInterceptors, "requestInterceptors for %s", target);
    this.logger = checkNotNull(logger, "logger for %s", target);
    this.logLevel = checkNotNull(logLevel, "logLevel for %s", target);
    this.metadata = checkNotNull(metadata, "metadata for %s", target);
    this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
    this.options = checkNotNull(options, "options for %s", target);
    this.errorDecoder = checkNotNull(errorDecoder, "errorDecoder for %s", target);
    this.decoder = checkNotNull(decoder, "decoder for %s", target);
    this.decode404 = decode404;
    this.closeAfterDecode = closeAfterDecode;
    this.propagationPolicy = propagationPolicy;
  }

  /**
   * 通过给定参数 调用方法并返回结果
   * @param argv
   * @return
   * @throws Throwable
   */
  @Override
  public Object invoke(Object[] argv) throws Throwable {
    // 尝试通过 给定的对象初始化 requestTemplate 因为每次传入的 argv 都会发生变化 导致生成的requestTemplate url信息也会发生变化所以没有做缓存
    RequestTemplate template = buildTemplateFromArgs.create(argv);
    // 从参数中寻找 Options 实现类 如果没有的话 就使用内置的 option
    Options options = findOptions(argv);
    // 创建副本对象
    Retryer retryer = this.retryer.clone();
    while (true) {
      try {
        // 发起请求 并返回结果
        return executeAndDecode(template, options);
      } catch (RetryableException e) {
        try {
          // 捕获到可重试异常时 判断是否 满足重试条件
          retryer.continueOrPropagate(e);
        } catch (RetryableException th) {
          // 代表不满足条件
          Throwable cause = th.getCause();
          if (propagationPolicy == UNWRAP && cause != null) {
            throw cause;
          } else {
            throw th;
          }
        }
        if (logLevel != Logger.Level.NONE) {
          logger.logRetry(metadata.configKey(), logLevel);
        }
        // 当 判断可以重试时 就会进入下次循环
        continue;
      }
    }
  }

  /**
   * 发起 http请求 并返回resp 对象
   * @param template
   * @param options
   * @return
   * @throws Throwable
   */
  Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
    Request request = targetRequest(template);

    if (logLevel != Logger.Level.NONE) {
      logger.logRequest(metadata.configKey(), logLevel, request);
    }

    Response response;
    long start = System.nanoTime();
    try {
      // 通过client 对象发起http 请求  内部会创建 JDK 的 HTTPUrlConnection
      response = client.execute(request, options);
    } catch (IOException e) {
      if (logLevel != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime(start));
      }
      // 首先将异常包装成 重试异常 ribbon 和 hystrix 本身是不具备重试功能的 这里就需要借助 feign 的 重试机制
      throw errorExecuting(request, e);
    }
    // 代表本次请求时间
    long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    boolean shouldClose = true;
    try {
      if (logLevel != Logger.Level.NONE) {
        // 打印一系列 日志后返回 resp
        response =
            logger.logAndRebufferResponse(metadata.configKey(), logLevel, response, elapsedTime);
      }
      // 判断返回类型  下面这些分支暂时没明白
      if (Response.class == metadata.returnType()) {
        if (response.body() == null) {
          return response;
        }
        // 代表数据一次没有装完
        if (response.body().length() == null ||
            response.body().length() > MAX_RESPONSE_BUFFER_SIZE) {
          shouldClose = false;
          return response;
        }
        // Ensure the response body is disconnected
        byte[] bodyData = Util.toByteArray(response.body().asInputStream());
        return response.toBuilder().body(bodyData).build();
      }
      // 代表本次成功
      if (response.status() >= 200 && response.status() < 300) {
        if (void.class == metadata.returnType()) {
          return null;
        } else {
          // 代表 需要返回类型
          Object result = decode(response);
          // 代表 在解码后是否需要关闭
          shouldClose = closeAfterDecode;
          return result;
        }
        // 如果404 需要解码
      } else if (decode404 && response.status() == 404 && void.class != metadata.returnType()) {
        Object result = decode(response);
        shouldClose = closeAfterDecode;
        return result;
      } else {
        // 使用异常解码器
        throw errorDecoder.decode(metadata.configKey(), response);
      }
    } catch (IOException e) {
      if (logLevel != Logger.Level.NONE) {
        logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime);
      }
      throw errorReading(request, response, e);
    } finally {
      // 关闭连接
      if (shouldClose) {
        ensureClosed(response.body());
      }
    }
  }

  long elapsedTime(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  // 根据 requestTemplate 对象生成 req
  Request targetRequest(RequestTemplate template) {
    // 拦截器在这里发挥作用
    for (RequestInterceptor interceptor : requestInterceptors) {
      interceptor.apply(template);
    }
    // 层层拦截后 处理 template 对象  就是 解析template 对象 并将结果设置到 req 中
    return target.apply(template);
  }

  Object decode(Response response) throws Throwable {
    try {
      // 解析成需要的类型
      return decoder.decode(response, metadata.returnType());
    } catch (FeignException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new DecodeException(response.status(), e.getMessage(), response.request(), e);
    }
  }

  Options findOptions(Object[] argv) {
    if (argv == null || argv.length == 0) {
      return this.options;
    }
    return (Options) Stream.of(argv)
        .filter(o -> o instanceof Options)
        .findFirst()
        .orElse(this.options);
  }

  /**
   * 工厂对象
   */
  static class Factory {

    private final Client client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final boolean decode404;
    private final boolean closeAfterDecode;
    private final ExceptionPropagationPolicy propagationPolicy;

    Factory(Client client, Retryer retryer, List<RequestInterceptor> requestInterceptors,
        Logger logger, Logger.Level logLevel, boolean decode404, boolean closeAfterDecode,
        ExceptionPropagationPolicy propagationPolicy) {
      this.client = checkNotNull(client, "client");
      this.retryer = checkNotNull(retryer, "retryer");
      this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
      this.logger = checkNotNull(logger, "logger");
      this.logLevel = checkNotNull(logLevel, "logLevel");
      this.decode404 = decode404;
      this.closeAfterDecode = closeAfterDecode;
      this.propagationPolicy = propagationPolicy;
    }

    public MethodHandler create(Target<?> target,
                                MethodMetadata md,
                                RequestTemplate.Factory buildTemplateFromArgs,
                                Options options,
                                Decoder decoder,
                                ErrorDecoder errorDecoder) {
      return new SynchronousMethodHandler(target, client, retryer, requestInterceptors, logger,
          logLevel, md, buildTemplateFromArgs, options, decoder,
          errorDecoder, decode404, closeAfterDecode, propagationPolicy);
    }
  }
}
