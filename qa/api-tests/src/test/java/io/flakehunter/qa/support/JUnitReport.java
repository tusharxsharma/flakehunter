package io.flakehunter.qa.support;

import java.util.ArrayList;
import java.util.List;

/** Builds JUnit XML test data. Tests are written as "Suite.name", e.g. "CartTest.addsItem". */
public final class JUnitReport {

    private final List<String> cases = new ArrayList<>();

    public JUnitReport add(String qualifiedName, String outcome) {
        int dot = qualifiedName.lastIndexOf('.');
        String suite = dot < 0 ? "Default" : qualifiedName.substring(0, dot);
        String name = dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
        String body = switch (outcome.toLowerCase()) {
            case "passed", "p" -> "";
            case "failed", "f" -> "<failure message=\"" + escape(name + " failed: expected 1 but was 2") + "\"/>";
            case "skipped", "s" -> "<skipped/>";
            case "flaky" -> "<flakyFailure message=\"Timed out after 500 ms\"/>";
            default -> throw new IllegalArgumentException("Unknown outcome: " + outcome);
        };
        cases.add("<testcase classname=\"%s\" name=\"%s\" time=\"0.05\">%s</testcase>"
                .formatted(escape(suite), escape(name), body));
        return this;
    }

    public String build() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuites><testsuite name=\"acceptance\">"
                + String.join("", cases) + "</testsuite></testsuites>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
