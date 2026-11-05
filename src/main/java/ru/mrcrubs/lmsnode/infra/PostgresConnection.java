package ru.mrcrubs.lmsnode.infra;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

final class PostgresConnection {
    static Connection open() throws SQLException {
        try {
            URI uri = URI.create(Files.readString(Path.of(System.getenv("DATABASE_URL_FILE"))).strip());
            if (!uri.getScheme().equals("postgresql") || !uri.getRawQuery().contains("sslmode=verify-full"))
                throw new IllegalArgumentException("Verified PostgreSQL URL required");
            String[] identity = uri.getRawUserInfo().split(":", 2);
            Properties options = new Properties();
            options.setProperty("user", URLDecoder.decode(identity[0], StandardCharsets.UTF_8));
            options.setProperty("password", URLDecoder.decode(identity[1], StandardCharsets.UTF_8));
            options.setProperty("connectTimeout", "10");
            options.setProperty("socketTimeout", "60");
            options.setProperty("tcpKeepAlive", "true");
            options.setProperty("ApplicationName", "lms-node");
            return DriverManager.getConnection("jdbc:postgresql://"+uri.getHost()+":"+uri.getPort()+uri.getRawPath()+"?"+uri.getRawQuery(), options);
        } catch (Exception e) {
            // The original driver exception can include connection configuration.
            throw new SQLException("Verified PostgreSQL connection failed", "08001");
        }
    }
}
