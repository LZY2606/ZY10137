package ecology.review.web;

import java.util.Map;

public class ApiException extends RuntimeException {
  private final int status;
  private final Map<String, Object> body;

  public ApiException(int status, String message) {
    super(message);
    this.status = status;
    this.body = Map.of("error", message);
  }

  public ApiException(int status, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("error", message);
    payload.putAll(details);
    this.body = Map.copyOf(payload);
  }

  public int getStatus() {
    return status;
  }

  public Map<String, Object> getBody() {
    return body;
  }
}
