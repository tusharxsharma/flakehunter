package io.flakehunter.api.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for JUnit XML reports used as test data.
 *
 * <pre>{@code
 * String xml = JUnitXml.suite("com.shop.CartTest")
 *         .passed("addsItem")
 *         .failed("appliesDiscount", "expected 90 but was 100")
 *         .build();
 * }</pre>
 */
public final class JUnitXml {

    private final String suite;
    private final List<String> cases = new ArrayList<>();

    private JUnitXml(String suite) {
        this.suite = suite;
    }

    public static JUnitXml suite(String suite) {
        return new JUnitXml(suite);
    }

    public JUnitXml passed(String name) {
        cases.add("<testcase classname=\"%s\" name=\"%s\" time=\"0.010\"/>".formatted(esc(suite), esc(name)));
        return this;
    }

    public JUnitXml failed(String name, String message) {
        cases.add("<testcase classname=\"%s\" name=\"%s\" time=\"0.020\"><failure message=\"%s\"/></testcase>"
                .formatted(esc(suite), esc(name), esc(message)));
        return this;
    }

    public JUnitXml skipped(String name) {
        cases.add("<testcase classname=\"%s\" name=\"%s\"><skipped/></testcase>".formatted(esc(suite), esc(name)));
        return this;
    }

    /** Failed on the first attempt, passed on Surefire's automatic rerun. */
    public JUnitXml passedOnRetry(String name, String firstAttemptMessage) {
        cases.add(("<testcase classname=\"%s\" name=\"%s\" time=\"0.030\">"
                + "<flakyFailure message=\"%s\"/></testcase>")
                .formatted(esc(suite), esc(name), esc(firstAttemptMessage)));
        return this;
    }

    public JUnitXml outcome(String name, boolean passed) {
        return passed ? passed(name) : failed(name, name + " failed");
    }

    public String build() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"%s\" tests=\"%d\">\n%s\n</testsuite>\n"
                .formatted(esc(suite), cases.size(), String.join("\n", cases));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
