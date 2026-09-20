package ecology.review.service;

import ecology.review.web.ApiException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

final class Support {
  static final DateTimeFormatter LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
      .appendPattern("yyyy-MM-dd['T'][' ']HH:mm[:ss]")
      .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
      .toFormatter();

  private Support() {
  }

  static Instant parseInstant(String value, String fallbackZone) {
    if (value == null || value.isBlank()) {
      throw new ApiException(400, "observed_at is required");
    }
    String trimmed = value.trim();
    if (trimmed.endsWith("Z") || trimmed.contains("+") || hasOffsetMinus(trimmed)) {
      return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    }
    LocalDateTime local = LocalDateTime.parse(trimmed, LOCAL_DATE_TIME);
    String zone = fallbackZone == null || fallbackZone.isBlank() ? "UTC" : fallbackZone;
    return local.atZone(ZoneId.of(zone)).toInstant();
  }

  static Instant zonedInstant(String localDateTime, String zone) {
    return LocalDateTime.parse(localDateTime, LOCAL_DATE_TIME)
        .atZone(ZoneId.of(zone))
        .toInstant();
  }

  static String normalizeCode(String value) {
    return value == null ? "" : value.trim().toUpperCase().replace(" ", "");
  }

  static double jaroWinklerSimilarity(String left, String right) {
    String a = normalizeCode(left);
    String b = normalizeCode(right);
    if (a.equals(b)) {
      return 1.0;
    }
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    int matchDistance = Math.max(a.length(), b.length()) / 2 - 1;
    boolean[] aMatches = new boolean[a.length()];
    boolean[] bMatches = new boolean[b.length()];
    int matches = 0;
    for (int i = 0; i < a.length(); i++) {
      int start = Math.max(0, i - matchDistance);
      int end = Math.min(i + matchDistance + 1, b.length());
      for (int j = start; j < end; j++) {
        if (bMatches[j] || a.charAt(i) != b.charAt(j)) {
          continue;
        }
        aMatches[i] = true;
        bMatches[j] = true;
        matches++;
        break;
      }
    }
    if (matches == 0) {
      return 0.0;
    }
    int transpositions = 0;
    int k = 0;
    for (int i = 0; i < a.length(); i++) {
      if (!aMatches[i]) {
        continue;
      }
      while (!bMatches[k]) {
        k++;
      }
      if (a.charAt(i) != b.charAt(k)) {
        transpositions++;
      }
      k++;
    }
    transpositions /= 2;
    double jaro = ((double) matches / a.length()
        + (double) matches / b.length()
        + (double) (matches - transpositions) / matches) / 3.0;
    int prefix = 0;
    int limit = Math.min(4, Math.min(a.length(), b.length()));
    for (int i = 0; i < limit && a.charAt(i) == b.charAt(i); i++) {
      prefix++;
    }
    return jaro + prefix * 0.1 * (1.0 - jaro);
  }

  static double tokenSimilarity(String left, String right) {
    java.util.Set<String> a = tokens(left);
    java.util.Set<String> b = tokens(right);
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    java.util.Set<String> intersection = new java.util.HashSet<>(a);
    intersection.retainAll(b);
    return 2.0 * intersection.size() / (a.size() + b.size());
  }

  static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
    double earthRadius = 6_371_000.0;
    double phi1 = Math.toRadians(lat1);
    double phi2 = Math.toRadians(lat2);
    double deltaPhi = Math.toRadians(lat2 - lat1);
    double deltaLambda = Math.toRadians(lon2 - lon1);
    double h = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
        + Math.cos(phi1) * Math.cos(phi2)
        * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
    return 2 * earthRadius * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
  }

  static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static boolean hasOffsetMinus(String value) {
    int index = value.indexOf('-', 10);
    return index > 0;
  }

  private static java.util.Set<String> tokens(String value) {
    java.util.Set<String> result = new java.util.HashSet<>();
    if (value == null) {
      return result;
    }
    for (String token : value.toLowerCase(java.util.Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
      if (token.length() > 1) {
        result.add(token);
      }
    }
    return result;
  }
}
