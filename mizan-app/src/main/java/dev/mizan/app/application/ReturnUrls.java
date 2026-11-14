package dev.mizan.app.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/** Where a customer may be sent after paying: only to addresses that start with a configured prefix. */
public final class ReturnUrls {

  private ReturnUrls() {}

  public static boolean allowed(String url, List<String> prefixes) {
    if (url == null || url.length() > 2048 || !wellFormed(url)) {
      return false;
    }
    return prefixes.stream().anyMatch(prefix -> startsWith(url, prefix));
  }

  /**
   * A plain "starts with" would let {@code https://store.example.evil.com} pass for the prefix
   * {@code https://store.example}. So the prefix must end at a border: the end, or one of {@code / ? #}.
   */
  private static boolean startsWith(String url, String prefix) {
    // An empty prefix allows nothing: the address's first character is never a border.
    if (!url.startsWith(prefix)) {
      return false;
    }
    if (prefix.endsWith("/") || url.length() == prefix.length()) {
      return true;
    }
    char next = url.charAt(prefix.length());
    return next == '/' || next == '?' || next == '#';
  }

  /** An address with a host and without spaces or control characters: safe to put in a redirect. */
  private static boolean wellFormed(String url) {
    try {
      return new URI(url).getHost() != null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /** The address with {@code payment_id} added, before any fragment. */
  public static String withPaymentId(String url, String paymentId) {
    int hash = url.indexOf('#');
    String base = hash < 0 ? url : url.substring(0, hash);
    String fragment = hash < 0 ? "" : url.substring(hash);
    return base + (base.contains("?") ? "&" : "?") + "payment_id=" + paymentId + fragment;
  }
}
