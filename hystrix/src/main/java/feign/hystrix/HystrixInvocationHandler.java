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
package feign.hystrix;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommand.Setter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Target;
import feign.Util;
import rx.Completable;
import rx.Observable;
import rx.Single;
import static feign.Util.checkNotNull;

/**
 * 该对象增强了 fegin 的 动态代理处理器
 */
final class HystrixInvocationHandler implements InvocationHandler {

  /**
   * 该对象包含了 url name type 等信息
   */
  private final Target<?> target;
  /**
   * methodHandler 路由
   */
  private final Map<Method, MethodHandler> dispatch;
  /**
   * 接收异常对象 并返回实例
   */
  private final FallbackFactory<?> fallbackFactory; // Nullable
  /**
   * 降级方法映射
   */
  private final Map<Method, Method> fallbackMethodMap;
  /**
   * HystrixCommand 映射
   */
  private final Map<Method, Setter> setterMethodMap;

  HystrixInvocationHandler(Target<?> target, Map<Method, MethodHandler> dispatch,
      SetterFactory setterFactory, FallbackFactory<?> fallbackFactory) {
    this.target = checkNotNull(target, "target");
    this.dispatch = checkNotNull(dispatch, "dispatch");
    this.fallbackFactory = fallbackFactory;
    // 只是开放访问权限
    this.fallbackMethodMap = toFallbackMethod(dispatch);
    this.setterMethodMap = toSetters(setterFactory, target, dispatch.keySet());
  }

  /**
   * If the method param of InvocationHandler.invoke is not accessible, i.e in a package-private
   * interface, the fallback call in hystrix command will fail cause of access restrictions. But
   * methods in dispatch are copied methods. So setting access to dispatch method doesn't take
   * effect to the method in InvocationHandler.invoke. Use map to store a copy of method to invoke
   * the fallback to bypass this and reducing the count of reflection calls.
   *
   * @return cached methods map for fallback invoking
   */
  static Map<Method, Method> toFallbackMethod(Map<Method, MethodHandler> dispatch) {
    Map<Method, Method> result = new LinkedHashMap<Method, Method>();
    for (Method method : dispatch.keySet()) {
      method.setAccessible(true);
      result.put(method, method);
    }
    return result;
  }

  /**
   * Process all methods in the target so that appropriate setters are created.
   */
  static Map<Method, Setter> toSetters(SetterFactory setterFactory,
                                       Target<?> target,
                                       Set<Method> methods) {
    Map<Method, Setter> result = new LinkedHashMap<Method, Setter>();
    for (Method method : methods) {
      method.setAccessible(true);
      result.put(method, setterFactory.create(target, method));
    }
    return result;
  }

  /**
   * 核心逻辑
   * @param proxy
   * @param method
   * @param args
   * @return
   * @throws Throwable
   */
  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args)
      throws Throwable {
    // early exit if the invoked method is from java.lang.Object
    // code is the same as ReflectiveFeign.FeignInvocationHandler
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

    // 使用 hystrix 包装 命令
    HystrixCommand<Object> hystrixCommand =
            // 只使用 commandGroupKey 和 commandKey 来初始化
        new HystrixCommand<Object>(setterMethodMap.get(method)) {
          // run 代表实际执行的逻辑
          @Override
          protected Object run() throws Exception {
            try {
              // 走 feign的逻辑 (feign 自带重试)
              return HystrixInvocationHandler.this.dispatch.get(method).invoke(args);
            } catch (Exception e) {
              throw e;
            } catch (Throwable t) {
              throw (Error) t;
            }
          }

          // 当 hystrix 执行 失败时 会走降级
          @Override
          protected Object getFallback() {
            if (fallbackFactory == null) {
              return super.getFallback();
            }
            try {
              // 返回降级结果 看来降级对象一般是 api接口的 实现类 比如一个 MockXXX 这样接口本身方法执行失败时 该方法又可以直接在实现类找到匹配的实现
              Object fallback = fallbackFactory.create(getExecutionException());
              Object result = fallbackMethodMap.get(method).invoke(fallback, args);
              if (isReturnsHystrixCommand(method)) {
                return ((HystrixCommand) result).execute();
              } else if (isReturnsObservable(method)) {
                // Create a cold Observable
                return ((Observable) result).toBlocking().first();
              } else if (isReturnsSingle(method)) {
                // Create a cold Observable as a Single
                return ((Single) result).toObservable().toBlocking().first();
              } else if (isReturnsCompletable(method)) {
                ((Completable) result).await();
                return null;
              } else if (isReturnsCompletableFuture(method)) {
                return ((Future) result).get();
              } else {
                return result;
              }
            } catch (IllegalAccessException e) {
              // shouldn't happen as method is public due to being an interface
              throw new AssertionError(e);
            } catch (InvocationTargetException | ExecutionException e) {
              // Exceptions on fallback are tossed by Hystrix
              throw new AssertionError(e.getCause());
            } catch (InterruptedException e) {
              // Exceptions on fallback are tossed by Hystrix
              Thread.currentThread().interrupt();
              throw new AssertionError(e.getCause());
            }
          }
        };

    if (Util.isDefault(method)) {
      return hystrixCommand.execute();
    } else if (isReturnsHystrixCommand(method)) {
      return hystrixCommand;
    } else if (isReturnsObservable(method)) {
      // Create a cold Observable
      return hystrixCommand.toObservable();
    } else if (isReturnsSingle(method)) {
      // Create a cold Observable as a Single
      return hystrixCommand.toObservable().toSingle();
    } else if (isReturnsCompletable(method)) {
      return hystrixCommand.toObservable().toCompletable();
    } else if (isReturnsCompletableFuture(method)) {
      return new ObservableCompletableFuture<>(hystrixCommand);
    }
    return hystrixCommand.execute();
  }

  private boolean isReturnsCompletable(Method method) {
    return Completable.class.isAssignableFrom(method.getReturnType());
  }

  private boolean isReturnsHystrixCommand(Method method) {
    return HystrixCommand.class.isAssignableFrom(method.getReturnType());
  }

  private boolean isReturnsObservable(Method method) {
    return Observable.class.isAssignableFrom(method.getReturnType());
  }

  private boolean isReturnsCompletableFuture(Method method) {
    return CompletableFuture.class.isAssignableFrom(method.getReturnType());
  }

  private boolean isReturnsSingle(Method method) {
    return Single.class.isAssignableFrom(method.getReturnType());
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof HystrixInvocationHandler) {
      HystrixInvocationHandler other = (HystrixInvocationHandler) obj;
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
