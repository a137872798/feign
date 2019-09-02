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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Controls reflective method dispatch.
 * 动态代理处理器工厂
 */
public interface InvocationHandlerFactory {

  /**
   * 用于创建动态代理执行器工厂
   * @param target
   * @param dispatch
   * @return
   */
  InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch);

  /**
   * Like {@link InvocationHandler#invoke(Object, java.lang.reflect.Method, Object[])}, except for a
   * single method.
   * 方法处理器  具体的架构应该是 目标对象 调用指定方法 通过匹配 dispatcher 找到对应的MethodHandler 对象 并执行invoker
   */
  interface MethodHandler {

    Object invoke(Object[] argv) throws Throwable;
  }

  static final class Default implements InvocationHandlerFactory {

    /**
     * 传入指定参数生成一个 动态代理处理器
     * @param target 该对象具备生成 req 的能力
     * @param dispatch 分发请求的路由表
     * @return
     */
    @Override
    public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
      return new ReflectiveFeign.FeignInvocationHandler(target, dispatch);
    }
  }
}
