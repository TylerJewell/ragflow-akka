package io.akka.ragflow.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The delimiter-field grammar SPEC-001 §3 rules 1-4 govern, ported from {@code rag/nlp/delim.py}.
 *
 * <p>A delimiter field is a string of bare characters and backtick-wrapped multi-character
 * tokens; parsing it never depends on locale, and matching is always case-sensitive (the source
 * never applies {@code re.I} here, question-log row 5).
 */
public final class Delimiter {

  private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");

  private Delimiter() {}

  /** {@code \r\n} and standalone {@code \r} normalized to {@code \n} (rule 3). */
  public static String normalizeNewlines(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return text.replace("\r\n", "\n").replace("\r", "\n");
  }

  /** True when the field contains at least one backtick-wrapped token (rule 6's trigger). */
  public static boolean hasWrappedDelimiter(String field) {
    if (field == null || field.isEmpty()) {
      return false;
    }
    return BACKTICK.matcher(field).find();
  }

  /**
   * Parses a delimiter field into delimiter strings, deduplicated and sorted longest-first (rules
   * 1-2). Insertion order is preserved for equal-length items by Java's stable sort.
   */
  public static List<String> parseField(String field) {
    if (field == null || field.isEmpty()) {
      return List.of();
    }
    String normalized = normalizeNewlines(field);
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    Matcher m = BACKTICK.matcher(normalized);
    int cursor = 0;
    while (m.find()) {
      for (int i = cursor; i < m.start(); i++) {
        seen.add(String.valueOf(normalized.charAt(i)));
      }
      String token = m.group(1);
      if (!token.isEmpty()) {
        seen.add(token);
      }
      cursor = m.end();
    }
    for (int i = cursor; i < normalized.length(); i++) {
      seen.add(String.valueOf(normalized.charAt(i)));
    }
    List<String> result = new ArrayList<>(seen);
    result.sort((a, b) -> b.length() - a.length());
    return result;
  }

  /**
   * Builds a {@code re.escape}-equivalent alternation pattern from a parsed delimiter list, ready
   * for a capturing split. Empty when {@code delimiters} is empty.
   */
  public static String compilePattern(List<String> delimiters) {
    if (delimiters.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < delimiters.size(); i++) {
      if (i > 0) {
        sb.append('|');
      }
      sb.append(Pattern.quote(delimiters.get(i)));
    }
    return sb.toString();
  }
}
