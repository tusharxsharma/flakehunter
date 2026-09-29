package io.flakehunter.api.ingestion;

import java.io.IOException;
import java.io.InputStream;

/** One uploaded report file. Decouples ingestion from how the bytes arrived (raw body or multipart). */
public interface ReportSource {

    String name();

    InputStream open() throws IOException;
}
