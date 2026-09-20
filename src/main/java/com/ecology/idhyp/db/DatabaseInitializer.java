package com.ecology.idhyp.db;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates the SQLite file (if needed) and applies the idempotent schema script. */
@Component
public class DatabaseInitializer {

    private final JdbcTemplate jdbc;
    private final String url;

    public DatabaseInitializer(JdbcTemplate jdbc,
                               @Value("${spring.datasource.url}") String url) {
        this.jdbc = jdbc;
        this.url = url;
    }

    @PostConstruct
    public void init() throws IOException {
        ensureParentDir();
        String sql = new ClassPathResource("db/schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String cleaned = stripComments(sql);
        for (String stmt : cleaned.split(";")) {
            if (!stmt.isBlank()) {
                jdbc.execute(stmt);
            }
        }
    }

    private void ensureParentDir() {
        String prefix = "jdbc:sqlite:";
        if (url.startsWith(prefix)) {
            Path p = Path.of(url.substring(prefix.length())).toAbsolutePath();
            if (p.getParent() != null) {
                p.getParent().toFile().mkdirs();
            }
        }
    }

    private static java.util.List<String> splitStatements(String sql) {
        return java.util.Arrays.stream(sql.split(";"))
                .map(DatabaseInitializer::stripComments)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String stripComments(String stmt) {
        StringBuilder out = new StringBuilder();
        for (String line : stmt.split("\\R")) {
            String trimmed = line.stripLeading();
            if (!trimmed.startsWith("--")) {
                out.append(line).append(' ');
            }
        }
        return out.toString().trim();
    }
}
