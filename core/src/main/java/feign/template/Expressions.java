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
package feign.template;

import feign.Util;
import feign.template.UriUtils.FragmentType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 静态工具类
 */
public final class Expressions {
  /**
   * 以 Pattern 作为key Expression 作为value
   */
  private static Map<Pattern, Class<? extends Expression>> expressions;

  static {
    expressions = new LinkedHashMap<>();

    /*
     * basic pattern for variable names. this is compliant with RFC 6570 Simple Expressions ONLY
     * with the following additional values allowed without required pct-encoding:
     *
     * - brackets - dashes
     *
     * see https://tools.ietf.org/html/rfc6570#section-2.3 for more information.
     * 输入  www.baidu.com?name=3&age=11  返回五处匹配 www.baidu.com
     *                                                 name
     *                                                 3
     *                                                 age
     *                                                 11
     */
    expressions.put(Pattern.compile("(\\w[-\\w.\\[\\]]*[ ]*)(:(.+))?"),
        SimpleExpression.class);
  }

  /**
   * 传入 原始数据 和 碎片类型生成表达式
   * @param value
   * @param type
   * @return
   */
  public static Expression create(final String value, final FragmentType type) {

    /* remove the start and end braces */
    final String expression = stripBraces(value);
    if (expression == null || expression.isEmpty()) {
      throw new IllegalArgumentException("an expression is required.");
    }

    // 找到匹配的第一个数据
    Optional<Entry<Pattern, Class<? extends Expression>>> matchedExpressionEntry =
        expressions.entrySet()
            .stream()
            .filter(entry -> entry.getKey().matcher(expression).matches())
            .findFirst();

    if (!matchedExpressionEntry.isPresent()) {
      /* not a valid expression */
      return null;
    }

    Entry<Pattern, Class<? extends Expression>> matchedExpression = matchedExpressionEntry.get();
    Pattern expressionPattern = matchedExpression.getKey();

    /* create a new regular expression matcher for the expression */
    String variableName = null;
    String variablePattern = null;
    Matcher matcher = expressionPattern.matcher(expression);
    if (matcher.matches()) {
      /* we have a valid variable expression, extract the name from the first group */
      // group(1) 代表括号中第一个内容 以此类推
      variableName = matcher.group(1).trim();
      if (matcher.group(2) != null && matcher.group(3) != null) {
        /* this variable contains an optional pattern */
        // 第三个元素才是 Pattern
        variablePattern = matcher.group(3);
      }
    }

    return new SimpleExpression(variableName, variablePattern, type);
  }

  /**
   * 剥离大括号
   * @param expression
   * @return
   */
  private static String stripBraces(String expression) {
    if (expression == null) {
      return null;
    }
    if (expression.startsWith("{") && expression.endsWith("}")) {
      return expression.substring(1, expression.length() - 1);
    }
    return expression;
  }

  /**
   * Expression that adheres to Simple String Expansion as outlined in <a
   * href="https://tools.ietf.org/html/rfc6570#section-3.2.2>Simple String Expansion (Level 1)</a>
   */
  static class SimpleExpression extends Expression {

    private final FragmentType type;

    SimpleExpression(String expression, String pattern, FragmentType type) {
      super(expression, pattern);
      this.type = type;
    }

    String encode(Object value) {
      return UriUtils.encodeReserved(value.toString(), type, Util.UTF_8);
    }

    /**
     * 使用传入的 variable 来拓展数据
     * @param variable
     * @param encode
     * @return
     */
    @Override
    String expand(Object variable, boolean encode) {
      StringBuilder expanded = new StringBuilder();
      if (Iterable.class.isAssignableFrom(variable.getClass())) {
        List<String> items = new ArrayList<>();
        for (Object item : ((Iterable) variable)) {
          items.add((encode) ? encode(item) : item.toString());
        }
        expanded.append(String.join(Template.COLLECTION_DELIMITER, items));
      } else {
        // 如果需要编码 将数据编码后返回
        expanded.append((encode) ? encode(variable) : variable);
      }

      /* return the string value of the variable */
      String result = expanded.toString();
      // 必须要保证能符合匹配条件
      if (!this.matches(result)) {
        throw new IllegalArgumentException("Value " + expanded
            + " does not match the expression pattern: " + this.getPattern());
      }
      return result;
    }
  }
}
