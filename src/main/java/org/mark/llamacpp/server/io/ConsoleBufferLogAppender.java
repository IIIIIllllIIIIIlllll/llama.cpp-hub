package org.mark.llamacpp.server.io;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.mark.llamacpp.server.LlamaServer;

public class ConsoleBufferLogAppender extends AbstractAppender {

    private static final String APPENDER_NAME = "ConsoleBufferLogAppender";
    private static final String DEFAULT_LOG_PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} - %msg%n";
    private static final String RAW_PROCESS_LOGGER_NAME = "LLAMA_CPP_RAW";

    protected ConsoleBufferLogAppender(String name, Filter filter, Layout<? extends Serializable> layout) {
        super(name, filter, layout, true, null);
    }

    @Override
    public void append(LogEvent event) {
        if (event == null) {
            return;
        }
        String loggerName = event.getLoggerName();
        if ("STDOUT".equals(loggerName) || "STDERR".equals(loggerName) || RAW_PROCESS_LOGGER_NAME.equals(loggerName)) {
            return;
        }
        byte[] bytes = getLayout().toByteArray(event.toImmutable());
        String line = new String(bytes, StandardCharsets.UTF_8);
        line = stripTrailingLineBreaks(line);
        if (!line.isEmpty()) {
            LlamaServer.sendConsoleLineEvent(null, line);
        }
    }

    private static String stripTrailingLineBreaks(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (c == '\n' || c == '\r') {
                end--;
                continue;
            }
            break;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    public static synchronized void install() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        if (configuration.getAppender(APPENDER_NAME) != null) {
            return;
        }
        PatternLayout layout = PatternLayout.newBuilder()
                .withConfiguration(configuration)
                .withPattern(resolvePattern(configuration))
                .build();
        ConsoleBufferLogAppender appender = new ConsoleBufferLogAppender(APPENDER_NAME, null, layout);
        appender.start();
        configuration.addAppender(appender);
        LoggerConfig rootLogger = configuration.getRootLogger();
        rootLogger.addAppender(appender, null, null);
        context.updateLoggers();
    }

    private static String resolvePattern(Configuration configuration) {
        if (configuration == null) {
            return DEFAULT_LOG_PATTERN;
        }
        String pattern = configuration.getStrSubstitutor().replace("${LOG_PATTERN}");
        if (pattern == null || pattern.isBlank() || "${LOG_PATTERN}".equals(pattern)) {
            return DEFAULT_LOG_PATTERN;
        }
        return pattern;
    }
}
