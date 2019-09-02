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

import feign.InvocationHandlerFactory.MethodHandler;
import org.jvnet.animal_sniffer.IgnoreJRERequirement;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Handles default methods by directly invoking the default method code on the interface. The bindTo
 * method must be called on the result before invoke is called.
 * 默认的方法处理器
 */
@IgnoreJRERequirement
final class DefaultMethodHandler implements MethodHandler {

  // 注意内部维护的是 JDK -> methodHandle  也就是句柄对象 java7特性

  // Uses Java 7 MethodHandle based reflection. As default methods will only exist when
  // run on a Java 8 JVM this will not affect use on legacy JVMs.
  // When Feign upgrades to Java 7, remove the @IgnoreJRERequirement annotation.
  // 这个代表的是原始的句柄对象
  private final MethodHandle unboundHandle;

  // handle is effectively final after bindTo has been called.
  // 该对象是 unboundHandle 绑定执行对象后生成的 句柄对象 具备真正执行方法的能力
  private MethodHandle handle;

  /**
   * 传入一个指定的 method 对象 并初始化 处理器
   * @param defaultMethod
   */
  public DefaultMethodHandler(Method defaultMethod) {
    try {
      // 获取修饰该方法的 class
      Class<?> declaringClass = defaultMethod.getDeclaringClass();
      // 反射获取 Lookup 的 IMPL_LOOKUP 该对象是 java7的 句柄对象 对应 invokeSpecial 指令
      Field field = Lookup.class.getDeclaredField("IMPL_LOOKUP");
      field.setAccessible(true);
      Lookup lookup = (Lookup) field.get(null);

      // 生成 句柄对象
      this.unboundHandle = lookup.unreflectSpecial(defaultMethod, declaringClass);
    } catch (NoSuchFieldException ex) {
      throw new IllegalStateException(ex);
    } catch (IllegalAccessException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /**
   * Bind this handler to a proxy object. After bound, DefaultMethodHandler#invoke will act as if it
   * was called on the proxy object. Must be called once and only once for a given instance of
   * DefaultMethodHandler
   * 为句柄对象绑定 执行者
   */
  public void bindTo(Object proxy) {
    if (handle != null) {
      throw new IllegalStateException(
          "Attempted to rebind a default method handler that was already bound");
    }
    handle = unboundHandle.bindTo(proxy);
  }

  /**
   * Invoke this method. DefaultMethodHandler#bindTo must be called before the first time invoke is
   * called.
   * 通过传入参数 执行
   */
  @Override
  public Object invoke(Object[] argv) throws Throwable {
    if (handle == null) {
      throw new IllegalStateException(
          "Default method handler invoked before proxy has been bound.");
    }
    return handle.invokeWithArguments(argv);
  }
}
