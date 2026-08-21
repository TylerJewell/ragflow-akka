package io.akka.ragflow.domain;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Token counting over the cl100k_base encoding, matching the source's {@code
 * num_tokens_from_string} (question-log row 14; {@code common/token_utils.py:45,126-132}).
 */
public final class TokenCounter {

  private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
  private static final Encoding CL100K = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

  private TokenCounter() {}

  public static int count(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return CL100K.countTokens(text);
  }
}
