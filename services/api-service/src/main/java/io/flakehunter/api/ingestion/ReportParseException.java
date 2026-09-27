package io.flakehunter.api.ingestion;

/** The uploaded report is not valid, safe JUnit XML. Mapped to HTTP 422. */
public class ReportParseException extends RuntimeException {

    public ReportParseException(String message) {
        super(message);
    }

    public ReportParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
