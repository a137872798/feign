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

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Cloned for each invocation to {@link Client#execute(Request, feign.Request.Options)}.
 * Implementations may keep state to determine if retry operations should continue or not.
 * 判断是否应该在之后 进行重试
 */
public interface Retryer extends Cloneable {

  /**
   * if retry is permitted, return (possibly after sleeping). Otherwise propagate the exception.
   * 判断是否允许重试 允许的话 设置 之后重试时间 否则 传递异常
   */
  void continueOrPropagate(RetryableException e);

  Retryer clone();

  /**
   * 默认实现
   */
  class Default implements Retryer {

    /**
     * 最大重试次数
     */
    private final int maxAttempts;
    /**
     * 每次重试的间隔
     */
    private final long period;
    /**
     * 允许设置的最大间隔
     */
    private final long maxPeriod;
    /**
     * 当前重试次数
     */
    int attempt;
    /**
     * 记录睡眠总时间
     */
    long sleptForMillis;

    public Default() {
      this(100, SECONDS.toMillis(1), 5);
    }

    public Default(long period, long maxPeriod, int maxAttempts) {
      this.period = period;
      this.maxPeriod = maxPeriod;
      this.maxAttempts = maxAttempts;
      this.attempt = 1;
    }

    // visible for testing;
    protected long currentTimeMillis() {
      return System.currentTimeMillis();
    }

    @Override
    public void continueOrPropagate(RetryableException e) {
      // 当前重试次数超过最大值 直接抛出异常
      if (attempt++ >= maxAttempts) {
        throw e;
      }

      long interval;
      if (e.retryAfter() != null) {
        // 获取下次重试时间 到当前时间的间隔
        interval = e.retryAfter().getTime() - currentTimeMillis();
        // 超过最大延迟 更新间隔时间
        if (interval > maxPeriod) {
          interval = maxPeriod;
        }
        if (interval < 0) {
          return;
        }
      } else {
        // 生成一个间隔时间
        interval = nextMaxInterval();
      }
      try {
        Thread.sleep(interval);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        throw e;
      }
      sleptForMillis += interval;
    }

    /**
     * Calculates the time interval to a retry attempt. <br>
     * The interval increases exponentially with each attempt, at a rate of nextInterval *= 1.5
     * (where 1.5 is the backoff factor), to the maximum interval.
     *
     * @return time in nanoseconds from now until the next attempt.
     */
    long nextMaxInterval() {
      long interval = (long) (period * Math.pow(1.5, attempt - 1));
      return interval > maxPeriod ? maxPeriod : interval;
    }

    @Override
    public Retryer clone() {
      return new Default(period, maxPeriod, maxAttempts);
    }
  }

  /**
   * Implementation that never retries request. It propagates the RetryableException.
   * 总是抛出异常的对象
   */
  Retryer NEVER_RETRY = new Retryer() {

    @Override
    public void continueOrPropagate(RetryableException e) {
      throw e;
    }

    @Override
    public Retryer clone() {
      return this;
    }
  };
}
