package io.flakehunter.api.ingestion;

import io.flakehunter.api.domain.TestStatus;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Streaming (StAX) parser for JUnit XML, the de-facto CI report format produced by
 * Maven Surefire, Gradle, pytest, Jest, Playwright, Cypress and most other runners.
 *
 * <p>Design choices:
 * <ul>
 *   <li><b>Streaming, not DOM</b>: memory stays flat regardless of report size.</li>
 *   <li><b>Secure by default</b>: DTDs and external entities are disabled and any DOCTYPE is
 *       rejected, which blocks XXE and "billion laughs" entity-expansion attacks.</li>
 *   <li><b>Tolerant of dialects</b>: nested suites, missing classname, locale-formatted times,
 *       and Surefire rerun elements are all handled.</li>
 * </ul>
 */
public final class JUnitXmlParser {

    private static final int MAX_NAME_LENGTH = 512;
    private static final XMLInputFactory FACTORY = createFactory();

    private final int maxMessageLength;

    public JUnitXmlParser(int maxMessageLength) {
        this.maxMessageLength = maxMessageLength;
    }

    private static XMLInputFactory createFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    public List<ParsedTestCase> parse(InputStream input) {
        XMLStreamReader reader = null;
        try {
            reader = FACTORY.createXMLStreamReader(input);
            return readDocument(reader);
        } catch (XMLStreamException e) {
            throw new ReportParseException("Malformed XML: " + e.getMessage(), e);
        } finally {
            closeQuietly(reader);
        }
    }

    private List<ParsedTestCase> readDocument(XMLStreamReader reader) throws XMLStreamException {
        List<ParsedTestCase> cases = new ArrayList<>();
        Deque<String> suiteNames = new ArrayDeque<>();
        Deque<String> suiteFiles = new ArrayDeque<>();
        CaseBuilder current = null;
        StringBuilder failureText = null;
        boolean rootSeen = false;

        while (reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.DTD ->
                        throw new ReportParseException("DOCTYPE declarations are not allowed in reports");
                case XMLStreamConstants.START_ELEMENT -> {
                    String element = reader.getLocalName();
                    if (!rootSeen) {
                        rootSeen = true;
                        if (!element.equals("testsuites") && !element.equals("testsuite")) {
                            throw new ReportParseException(
                                    "Root element must be <testsuites> or <testsuite> but was <" + element + ">");
                        }
                    }
                    switch (element) {
                        case "testsuite" -> {
                            suiteNames.push(blankToEmpty(attr(reader, "name")));
                            suiteFiles.push(blankToEmpty(attr(reader, "file")));
                        }
                        case "testcase" -> current = new CaseBuilder(
                                attr(reader, "name"),
                                attr(reader, "classname"),
                                attr(reader, "file"),
                                parseSeconds(attr(reader, "time")));
                        case "failure", "error" -> {
                            if (current != null) {
                                current.markFailed(attr(reader, "message"));
                                failureText = new StringBuilder();
                            }
                        }
                        case "flakyFailure", "flakyError", "rerunFailure", "rerunError" -> {
                            if (current != null) {
                                current.recordFailedAttempt(attr(reader, "message"));
                            }
                        }
                        case "skipped" -> {
                            if (current != null) {
                                current.markSkipped();
                            }
                        }
                        default -> {
                            // properties, system-out, etc. are not needed
                        }
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (failureText != null && failureText.length() < maxMessageLength) {
                        failureText.append(reader.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    switch (reader.getLocalName()) {
                        case "failure", "error" -> {
                            if (current != null && failureText != null) {
                                current.appendDetails(failureText.toString());
                            }
                            failureText = null;
                        }
                        case "testcase" -> {
                            if (current != null) {
                                cases.add(current.build(suiteNames.peek(), suiteFiles.peek()));
                                current = null;
                            }
                        }
                        case "testsuite" -> {
                            suiteNames.poll();
                            suiteFiles.poll();
                        }
                        default -> {
                            // nothing to do
                        }
                    }
                }
                default -> {
                    // comments, whitespace, processing instructions
                }
            }
        }
        if (!rootSeen) {
            throw new ReportParseException("Report is empty");
        }
        return cases;
    }

    static long parseSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            // Some runners emit locale-formatted numbers such as "1,234.5"
            double seconds = Double.parseDouble(raw.replace(",", "").trim());
            if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) {
                return 0L;
            }
            return Math.round(seconds * 1000);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String attr(XMLStreamReader reader, String name) {
        return reader.getAttributeValue(null, name);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void closeQuietly(XMLStreamReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // best effort
            }
        }
    }

    /** Mutable accumulator for the testcase currently being read. */
    private final class CaseBuilder {
        private final String name;
        private final String classname;
        private final String file;
        private final long durationMs;
        private TestStatus status = TestStatus.PASSED;
        private String message;
        private String details;
        private int extraFailedAttempts;
        private String firstAttemptMessage;

        CaseBuilder(String name, String classname, String file, long durationMs) {
            if (name == null || name.isBlank()) {
                throw new ReportParseException("Found a <testcase> without a name attribute");
            }
            this.name = name.trim();
            this.classname = classname;
            this.file = file;
            this.durationMs = durationMs;
        }

        void markFailed(String failureMessage) {
            status = TestStatus.FAILED;
            if (message == null) {
                message = failureMessage;
            }
        }

        void markSkipped() {
            if (status != TestStatus.FAILED) {
                status = TestStatus.SKIPPED;
            }
        }

        void recordFailedAttempt(String attemptMessage) {
            extraFailedAttempts++;
            if (firstAttemptMessage == null) {
                firstAttemptMessage = attemptMessage;
            }
        }

        void appendDetails(String text) {
            String trimmed = text.trim();
            if (!trimmed.isEmpty() && details == null) {
                details = trimmed;
            }
        }

        ParsedTestCase build(String enclosingSuite, String enclosingFile) {
            String suite = classname != null && !classname.isBlank() ? classname.trim()
                    : enclosingSuite != null && !enclosingSuite.isEmpty() ? enclosingSuite
                    : "(root)";
            String resolvedFile = file != null && !file.isBlank() ? file.trim()
                    : enclosingFile != null && !enclosingFile.isEmpty() ? enclosingFile
                    : null;
            return new ParsedTestCase(
                    truncate(suite, MAX_NAME_LENGTH),
                    truncate(name, MAX_NAME_LENGTH),
                    resolvedFile == null ? null : truncate(resolvedFile, 1024),
                    status,
                    durationMs,
                    buildMessage(),
                    extraFailedAttempts);
        }

        private String buildMessage() {
            String text;
            if (status == TestStatus.FAILED) {
                if (message != null && details != null && !details.startsWith(message)) {
                    text = message + "\n" + details;
                } else {
                    text = details != null ? details : message;
                }
            } else {
                text = firstAttemptMessage;
            }
            return text == null || text.isBlank() ? null : truncate(text.trim(), maxMessageLength);
        }
    }
}
