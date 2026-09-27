package com.divakar.opspilot.agent;

import com.google.adk.tools.Annotations.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tool used by the Log Analyst agent.
 *
 * Today it reads sample log files from src/main/resources/logs. Later you can swap the body
 * for a real source (Kubernetes pod logs, CloudWatch, Loki) without changing the agent.
 */
public final class LogTools {

    /** Only simple service names are allowed, so the model can never read arbitrary files. */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9-]{2,50}$");
    private static final int MAX_LINES = 60;

    private LogTools() {
    }

    @Schema(name = "fetchLogs",
            description = "Fetches the most recent log lines for a service, with counts of ERROR and WARN lines.")
    public static Map<String, Object> fetchLogs(
            @Schema(name = "serviceName",
                    description = "Service name in lowercase with hyphens, e.g. payment-service")
            String serviceName) {

        if (serviceName == null || !SAFE_NAME.matcher(serviceName).matches()) {
            return Map.of("status", "error",
                    "message", "Invalid service name. Use lowercase letters, digits and hyphens only.");
        }

        String path = "logs/" + serviceName + ".log";
        try (InputStream in = LogTools.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return Map.of("status", "not_found",
                        "message", "No logs found for service " + serviceName);
            }
            List<String> lines = Arrays.asList(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
            List<String> recent = lines.subList(Math.max(0, lines.size() - MAX_LINES), lines.size());

            long errors = recent.stream().filter(l -> l.contains(" ERROR ")).count();
            long warnings = recent.stream().filter(l -> l.contains(" WARN ")).count();

            return Map.of(
                    "status", "ok",
                    "serviceName", serviceName,
                    "errorCount", errors,
                    "warnCount", warnings,
                    "logs", String.join("\n", recent));
        } catch (IOException e) {
            return Map.of("status", "error", "message", "Could not read logs: " + e.getMessage());
        }
    }
}
