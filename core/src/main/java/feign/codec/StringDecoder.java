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
package feign.codec;

import java.io.IOException;
import java.lang.reflect.Type;
import feign.Response;
import feign.Util;
import static java.lang.String.format;

/**
 * String 类型的解码器
 */
public class StringDecoder implements Decoder {

  @Override
  public Object decode(Response response, Type type) throws IOException {
    // 获取 res 的数据
    Response.Body body = response.body();
    if (body == null) {
      return null;
    }
    // 要求 type 必须是 string 类型
    if (String.class.equals(type)) {
      return Util.toString(body.asReader());
    }
    throw new DecodeException(response.status(),
        format("%s is not a type supported by this decoder.", type), response.request());
  }
}
