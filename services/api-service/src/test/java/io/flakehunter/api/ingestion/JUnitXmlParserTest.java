package io.flakehunter.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flakehunter.api.domain.TestStatus;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class JUnitXmlParserTest {

    private final JUnitXmlParser parser = new JUnitXmlParser(4000);

    private List<ParsedTestCase> parseResource(String name) {
        InputStream in = getClass().getResourceAsStream("/reports/" + name);
        assertThat(in).as("fixture %s", name).isNotNull();
        return parser.parse(in);
    }

    private List<ParsedTestCase> parseString(String xml) {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static ParsedTestCase byName(List<ParsedTestCase> cases, String name) {
        return cases.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("Maven Surefire report")
    class Surefire {

        private final List<ParsedTestCase> cases = parseResource("surefire-cart.xml");

        @Test
        void readsEveryTestCase() {
            assertThat(cases).extracting(ParsedTestCase::name).containsExactly(
                    "addsItemToCart", "appliesDiscountCode", "loadsSavedCart", "checksOutWithWallet", "recalculatesTax");
            assertThat(cases).allSatisfy(c -> assertThat(c.suite()).isEqualTo("com.shop.cart.CartServiceTest"));
        }

        @Test
        void mapsPassedFailedErrorAndSkipped() {
            assertThat(byName(cases, "addsItemToCart").status()).isEqualTo(TestStatus.PASSED);
            assertThat(byName(cases, "appliesDiscountCode").status()).isEqualTo(TestStatus.FAILED);
            assertThat(byName(cases, "loadsSavedCart").status()).as("<error> counts as failed").isEqualTo(TestStatus.FAILED);
            assertThat(byName(cases, "checksOutWithWallet").status()).isEqualTo(TestStatus.SKIPPED);
        }

        @Test
        void combinesFailureMessageAndStackTrace() {
            ParsedTestCase failed = byName(cases, "appliesDiscountCode");
            assertThat(failed.failureMessage())
                    .startsWith("expected: <90.0> but was: <100.0>")
                    .contains("CartServiceTest.java:42");
        }

        @Test
        void convertsSecondsToMilliseconds() {
            assertThat(byName(cases, "appliesDiscountCode").durationMs()).isEqualTo(1504);
        }

        @Test
        void recordsFlakyFailureAsAnExtraAttemptOnAPassingTest() {
            ParsedTestCase retried = byName(cases, "recalculatesTax");
            assertThat(retried.status()).isEqualTo(TestStatus.PASSED);
            assertThat(retried.extraFailedAttempts()).isEqualTo(1);
            assertThat(retried.failureMessage()).contains("Timed out after 500 ms");
        }

        @Test
        void buildsStableTestKey() {
            assertThat(byName(cases, "addsItemToCart").testKey()).isEqualTo("com.shop.cart.CartServiceTest::addsItemToCart");
        }
    }

    @Test
    void parsesPytestReportWithParametrizedNames() {
        List<ParsedTestCase> cases = parseResource("pytest.xml");

        assertThat(cases).hasSize(3);
        assertThat(byName(cases, "test_charge[amex]").status()).isEqualTo(TestStatus.FAILED);
        assertThat(byName(cases, "test_charge[amex]").failureMessage()).contains("assert 402 == 200");
        assertThat(byName(cases, "test_charge[visa]").suite()).isEqualTo("tests.test_payments");
    }

    @Test
    void fallsBackToEnclosingSuiteAndFileWhenClassnameIsMissing() {
        List<ParsedTestCase> cases = parseResource("jest-nested.xml");

        ParsedTestCase nested = byName(cases, "shows an error for an empty email");
        assertThat(nested.suite()).as("innermost suite wins").isEqualTo("validation");

        ParsedTestCase direct = byName(cases, "submits credentials");
        assertThat(direct.suite()).isEqualTo("LoginForm");
        assertThat(direct.file()).isEqualTo("src/components/LoginForm.test.tsx");

        ParsedTestCase blankClassname = byName(cases, "formats currency");
        assertThat(blankClassname.suite()).isEqualTo("utils");
        assertThat(blankClassname.durationMs()).as("unparseable time").isZero();
    }

    @Test
    void usesRootSuiteNameWhenNothingElseIsAvailable() {
        List<ParsedTestCase> cases = parseString("<testsuites><testcase name=\"orphan\"/></testsuites>");
        assertThat(cases.getFirst().suite()).isEqualTo("(root)");
    }

    @Test
    void failedTestWithoutMessageAttributeUsesBodyText() {
        List<ParsedTestCase> cases = parseString("""
                <testsuite name="s"><testcase name="t"><failure>boom</failure></testcase></testsuite>""");
        assertThat(cases.getFirst().failureMessage()).isEqualTo("boom");
    }

    @Test
    void truncatesVeryLongFailureMessages() {
        JUnitXmlParser strict = new JUnitXmlParser(100);
        String longMessage = "x".repeat(10_000);
        List<ParsedTestCase> cases = strict.parse(new ByteArrayInputStream(("""
                <testsuite name="s"><testcase name="t"><failure message="%s"/></testcase></testsuite>"""
                .formatted(longMessage)).getBytes(StandardCharsets.UTF_8)));
        assertThat(cases.getFirst().failureMessage()).hasSize(100);
    }

    @Test
    void rerunFailureOnAFailingTestKeepsItFailed() {
        List<ParsedTestCase> cases = parseString("""
                <testsuite name="s"><testcase name="t">
                  <failure message="first"/>
                  <rerunFailure message="second"/>
                </testcase></testsuite>""");
        assertThat(cases.getFirst().status()).isEqualTo(TestStatus.FAILED);
        assertThat(cases.getFirst().extraFailedAttempts()).isEqualTo(1);
    }

    @Nested
    @DisplayName("Security and invalid input")
    class Rejections {

        @Test
        void rejectsDoctypeToPreventXxe() {
            assertThatThrownBy(() -> parseResource("xxe-attack.xml"))
                    .isInstanceOf(ReportParseException.class)
                    .hasMessageContaining("DOCTYPE");
        }

        @Test
        void rejectsMalformedXml() {
            assertThatThrownBy(() -> parseString("<testsuite><testcase name=\"a\">"))
                    .isInstanceOf(ReportParseException.class)
                    .hasMessageStartingWith("Malformed XML");
        }

        @Test
        void rejectsNonJUnitRootElement() {
            assertThatThrownBy(() -> parseString("<html><body/></html>"))
                    .isInstanceOf(ReportParseException.class)
                    .hasMessageContaining("<html>");
        }

        @Test
        void rejectsTestCaseWithoutName() {
            assertThatThrownBy(() -> parseString("<testsuite><testcase classname=\"x\"/></testsuite>"))
                    .isInstanceOf(ReportParseException.class)
                    .hasMessageContaining("without a name");
        }

        @Test
        void rejectsEmptyDocument() {
            assertThatThrownBy(() -> parseString(""))
                    .isInstanceOf(ReportParseException.class);
        }
    }

    @ParameterizedTest(name = "\"{0}\" seconds -> {1} ms")
    @CsvSource({
            "1.5, 1500",
            "0.0004, 0",
            "'1,234.5', 1234500",
            "not-a-number, 0",
            "-3, 0",
            "NaN, 0",
            "Infinity, 0"
    })
    void parsesDurations(String raw, long expectedMs) {
        assertThat(JUnitXmlParser.parseSeconds(raw)).isEqualTo(expectedMs);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingDurationIsZero(String raw) {
        assertThat(JUnitXmlParser.parseSeconds(raw)).isZero();
    }
}
