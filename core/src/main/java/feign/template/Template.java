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
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A Generic representation of a Template Expression as defined by
 * <a href="https://tools.ietf.org/html/rfc6570">RFC 6570</a>, with some relaxed rules, allowing the
 * concept to be used in areas outside of the uri.
 * feign 的一种模板
 */
public class Template {

  /*
   * special delimiter for collection based expansion, in an attempt to avoid accidental splitting
   * for resolved values. semi-colon was chosen because it is a reserved character that must be
   * pct-encoded and should not appear unencoded.
   */
  static final String COLLECTION_DELIMITER = ";";

  private static final Logger logger = Logger.getLogger(Template.class.getName());
  private static final Pattern QUERY_STRING_PATTERN = Pattern.compile("(?<!\\{)(\\?)");
  private final String template;
  /**
   * 是否允许不解析
   */
  private final boolean allowUnresolved;
  /**
   * 是否必须解析
   */
  private final EncodingOptions encode;
  /**
   * 斜线也编码
   */
  private final boolean encodeSlash;
  /**
   * 字符集
   */
  private final Charset charset;
  /**
   * 代表一组 "块"
   */
  private final List<TemplateChunk> templateChunks = new ArrayList<>();

  /**
   * Create a new Template.
   *
   * @param value of the template.
   * @param allowUnresolved if unresolved expressions should remain.
   * @param encode all values.
   * @param encodeSlash if slash characters should be encoded.
   */
  Template(
      String value, ExpansionOptions allowUnresolved, EncodingOptions encode, boolean encodeSlash,
      Charset charset) {
    if (value == null) {
      throw new IllegalArgumentException("template is required.");
    }
    this.template = value;
    this.allowUnresolved = ExpansionOptions.ALLOW_UNRESOLVED == allowUnresolved;
    // 是否需要编码
    this.encode = encode;
    // 斜线是否需要被编码
    this.encodeSlash = encodeSlash;
    this.charset = charset;
    this.parseTemplate();
  }

  /**
   * Expand the template.
   *
   * @param variables containing the values for expansion.
   * @return a fully qualified URI with the variables expanded.
   * 使用额外的参数进行拓展
   */
  public String expand(Map<String, ?> variables) {
    if (variables == null) {
      throw new IllegalArgumentException("variable map is required.");
    }

    /* resolve all expressions within the template */
    StringBuilder resolved = new StringBuilder();
    for (TemplateChunk chunk : this.templateChunks) {
      if (chunk instanceof Expression) {
        String resolvedExpression = this.resolveExpression((Expression) chunk, variables);
        if (resolvedExpression != null) {
          resolved.append(resolvedExpression);
        }
      } else {
        /* chunk is a literal value */
        resolved.append(chunk.getValue());
      }
    }
    return resolved.toString();
  }

  /**
   * 从 map 中寻找 expression 的替代数据并进行替换
   * @param expression
   * @param variables
   * @return
   */
  protected String resolveExpression(Expression expression, Map<String, ?> variables) {
    String resolved = null;
    Object value = variables.get(expression.getName());
    if (value != null) {
      String expanded = expression.expand(value, this.encode.isEncodingRequired());
      if (Util.isNotBlank(expanded)) {
        if (this.encodeSlash) { // "/"  都被替换成了 %2F
          logger.fine("Explicit slash decoding specified, decoding all slashes in uri");
          expanded = expanded.replaceAll("/", "%2F");
        }
        resolved = expanded;
      }
    } else {
      if (this.allowUnresolved) {
        /* unresolved variables are treated as literals */
        resolved = encode(expression.toString());
      }
    }
    return resolved;
  }

  /**
   * Uri Encode the value.
   *
   * @param value to encode.
   * @return the encoded value.
   */
  private String encode(String value) {
    return this.encode.isEncodingRequired() ? UriUtils.encode(value, this.charset) : value;
  }

  /**
   * Uri Encode the value.
   *
   * @param value to encode
   * @param query indicating this value is on a query string.
   * @return the encoded value
   */
  private String encode(String value, boolean query) {
    if (this.encode.isEncodingRequired()) {
                      // 针对查询参数的 加密
      return query ? UriUtils.queryEncode(value, this.charset)
              // 代表针对 path 的加密
          : UriUtils.pathEncode(value, this.charset);
    } else {
      return value;
    }
  }

  /**
   * Variable names contained in the template.
   *
   * @return a List of Variable Names.  这里就是 通过解析 url 中 {} 的内容 判断有哪些变量
   */
  public List<String> getVariables() {
    return this.templateChunks.stream()
            // 只需要 Expression 的子类
        .filter(templateChunk -> Expression.class.isAssignableFrom(templateChunk.getClass()))
            // 获取 name 属性
        .map(templateChunk -> ((Expression) templateChunk).getName())
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  /**
   * List of all Literals in the Template.
   *
   * @return list of Literal values.
   */
  public List<String> getLiterals() {
    return this.templateChunks.stream()
        .filter(templateChunk -> Literal.class.isAssignableFrom(templateChunk.getClass()))
        .map(TemplateChunk::toString)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  /**
   * Flag to indicate that this template is a literal string, with no variable expressions.
   *
   * @return true if this template is made up entirely of literal strings.
   */
  public boolean isLiteral() {
    return this.getVariables().isEmpty();
  }

  /**
   * Parse the template into {@link TemplateChunk}s.
   * 处理模板生成 chunk
   */
  private void parseTemplate() {
    /*
     * query string and path literals have different reserved characters and different encoding
     * requirements. to ensure compliance with RFC 6570, we'll need to encode query literals
     * differently from path literals. let's look at the template to see if it contains a query
     * string and if so, keep track of where it starts.
     */
    Matcher queryStringMatcher = QUERY_STRING_PATTERN.matcher(this.template);
    // 判断是否存在查询参数 如果是 生成 UrlTemplate 一般是 不会存在的 因为 之前已经尝试匹配过一次了
    if (queryStringMatcher.find()) {
      /*
       * the template contains a query string, split the template into two parts, the path and query
       */
      // 前面的 部分就是 url
      String path = this.template.substring(0, queryStringMatcher.start());
      // 后面的 代表 查询参数 name=123&age=111
      String query = this.template.substring(queryStringMatcher.end() - 1);
      // 按照特定符号进行解析
      this.parseFragment(path, false);
      this.parseFragment(query, true);
    } else {
      /* parse the entire template */
      this.parseFragment(this.template, false);
    }
  }

  /**
   * Parse a template fragment.
   *
   * @param fragment to parse
   * @param query if the fragment is part of a query string.
   *              解析碎片
   */
  private void parseFragment(String fragment, boolean query) {
    ChunkTokenizer tokenizer = new ChunkTokenizer(fragment);

    while (tokenizer.hasNext()) {
      /* check to see if we have an expression or a literal */
      String chunk = tokenizer.next();

      if (chunk.startsWith("{")) {
        /* it's an expression, defer encoding until resolution */
        FragmentType type = (query) ? FragmentType.QUERY : FragmentType.PATH_SEGMENT;

        // 该对象是 path 中 {xxx} 内部的 xxx 抽象生成的
        Expression expression = Expressions.create(chunk, type);
        if (expression == null) {
          this.templateChunks.add(Literal.create(encode(chunk, query)));
        } else {
          this.templateChunks.add(expression);
        }
      } else {
        /* it's a literal, pct-encode it */
        this.templateChunks.add(Literal.create(encode(chunk, query)));
      }
    }
  }

  @Override
  public String toString() {
    return this.templateChunks.stream()
        .map(TemplateChunk::getValue).collect(Collectors.joining());
  }

  public boolean encode() {
    return encode.isEncodingRequired();
  }

  boolean encodeSlash() {
    return encodeSlash;
  }

  /**
   * The Charset for the template.
   *
   * @return the Charset, if set. Defaults to UTF-8
   */
  public Charset getCharset() {
    return this.charset;
  }

  /**
   * Splits a Uri into Chunks that exists inside and outside of an expression, delimited by curly
   * braces "{}". Nested expressions are treated as literals, for example "foo{bar{baz}}" will be
   * treated as "foo, {bar{baz}}". Inspired by Apache CXF Jax-RS.
   * 使用 词法解析器对象   针对 url 中携带的 {} 占位符
   */
  static class ChunkTokenizer {
    // {} 中的内容
    private List<String> tokens = new ArrayList<>();
    private int index;

    ChunkTokenizer(String template) {
      boolean outside = true;
      int level = 0;
      int lastIndex = 0;
      int idx;

      /* loop through the template, character by character */
      for (idx = 0; idx < template.length(); idx++) {
        if (template.charAt(idx) == '{') {
          /* start of an expression */
          if (outside) {
            /* outside of an expression */
            if (lastIndex < idx) {
              /* this is the start of a new token */
              tokens.add(template.substring(lastIndex, idx));
            }
            lastIndex = idx;

            /*
             * no longer outside of an expression, additional characters will be treated as in an
             * expression
             */
            outside = false;
          } else {
            /* nested braces, increase our nesting level */
            level++;
          }
        } else if (template.charAt(idx) == '}' && !outside) {
          /* the end of an expression */
          if (level > 0) {
            /*
             * sometimes we see nested expressions, we only want the outer most expression
             * boundaries.
             */
            level--;
          } else {
            /* outermost boundary */
            if (lastIndex < idx) {
              /* this is the end of an expression token */
              tokens.add(template.substring(lastIndex, idx + 1));
            }
            lastIndex = idx + 1;

            /* outside an expression */
            outside = true;
          }
        }
      }
      if (lastIndex < idx) {
        /* grab the remaining chunk */
        tokens.add(template.substring(lastIndex, idx));
      }
    }

    public boolean hasNext() {
      return this.tokens.size() > this.index;
    }

    public String next() {
      if (hasNext()) {
        return this.tokens.get(this.index++);
      }
      throw new IllegalStateException("No More Elements");
    }
  }

  public enum EncodingOptions {
    REQUIRED(true), NOT_REQUIRED(false);

    private boolean shouldEncode;

    EncodingOptions(boolean shouldEncode) {
      this.shouldEncode = shouldEncode;
    }

    public boolean isEncodingRequired() {
      return this.shouldEncode;
    }
  }

  /**
   * 是否必须要解析
   */
  public enum ExpansionOptions {
    ALLOW_UNRESOLVED, REQUIRED
  }

}
