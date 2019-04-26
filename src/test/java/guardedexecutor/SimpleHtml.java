/*
 * Copyright 2019 by Justin T. Sampson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package guardedexecutor;

final class SimpleHtml {

  private final StringBuilder builder = new StringBuilder();
  private boolean startLine = true;
  private int indent = 0;

  private static String escape(Object text) {
    if (text == null) {
      return "";
    }
    String string = text.toString();
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < string.length(); i++) {
      char c = string.charAt(i);
      switch (c) {
        case '<':
          result.append("&lt;");
          break;
        case '>':
          result.append("&gt;");
          break;
        case '&':
          result.append("&amp;");
          break;
        case '"':
          result.append("&quot;");
          break;
        case '\'':
          result.append("&#39;");
          break;
        default:
          result.append(c);
      }
    }
    return result.toString();
  }

  private StringBuilder indent() {
    if (startLine) {
      for (int i = 0; i < indent; i++) {
        builder.append("  ");
      }
      startLine = false;
    }
    return builder;
  }

  SimpleHtml start(String tag, Object... attributes) {
    assert attributes.length % 2 == 0;
    indent().append("<").append(tag);
    for (int i = 0; i < attributes.length; i += 2) {
      String attributeName = (String) attributes[i];
      Object attributeValue = attributes[i + 1];
      if (attributeValue != null) {
        builder.append(" ").append(attributeName).append("=\"")
            .append(escape(attributeValue)).append("\"");
      }
    }
    builder.append(">");
    indent++;
    return this;
  }

  SimpleHtml nl() {
    if (!startLine) {
      builder.append("\n");
      startLine = true;
    }
    return this;
  }

  SimpleHtml end(String tag) {
    indent--;
    indent().append("</").append(tag).append(">");
    return this;
  }

  SimpleHtml text(Object... objects) {
    for (Object object : objects) {
      String escaped = escape(object);
      if (!escaped.isEmpty()) {
        indent().append(escaped);
      }
    }
    return this;
  }

  SimpleHtml nbsp() {
    indent().append("&nbsp;");
    return this;
  }

  SimpleHtml comment(Object... objects) {
    indent().append("<!-- ");
    for (Object object : objects) {
      if (object != null) {
        builder.append(object);
      }
    }
    builder.append(" -->");
    return this;
  }

  @Override
  public String toString() {
    return builder.toString();
  }

}
