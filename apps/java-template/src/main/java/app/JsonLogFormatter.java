package app;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * Logs: one JSON object per line on stdout, the shape the platform's log collector takes apart (its
 * RUNBOOK, Logs): {@code ts} becomes the line's time, {@code msg} its message, and every other key
 * ({@code level}, {@code request_id}, {@code route}, {@code status}...) a field to search by. The
 * MDC's entries and the event's key-value pairs become top-level keys, so each line logged while a
 * request is served carries its ID (RequestContext). The same keys as the Python and Rust
 * templates. application.properties turns it on for the console.
 */
public class JsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
  private final JsonWriter<ILoggingEvent> writer =
      JsonWriter.<ILoggingEvent>of(
              members -> {
                members.add("ts", e -> Instant.ofEpochMilli(e.getTimeStamp()).toString());
                members.add("level", e -> e.getLevel().toString().toLowerCase(Locale.ROOT));
                members.add("logger", ILoggingEvent::getLoggerName);
                members.add("msg", ILoggingEvent::getFormattedMessage);
                members.addMapEntries(JsonLogFormatter::fields);
                members.add("exc", JsonLogFormatter::stackTrace).whenNotNull();
              })
          .withNewLineAtEnd();

  private static Map<String, Object> fields(ILoggingEvent event) {
    Map<String, Object> fields = new TreeMap<>(event.getMDCPropertyMap());
    if (event.getKeyValuePairs() != null) {
      for (KeyValuePair pair : event.getKeyValuePairs()) {
        fields.put(pair.key, pair.value);
      }
    }
    return fields;
  }

  private static String stackTrace(ILoggingEvent event) {
    return event.getThrowableProxy() == null
        ? null
        : ThrowableProxyUtil.asString(event.getThrowableProxy());
  }

  @Override
  public String format(ILoggingEvent event) {
    return writer.writeToString(event);
  }
}
