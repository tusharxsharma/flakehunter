package io.flakehunter.qa.support;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/** Shared REST Assured configuration. The target environment comes from FLAKEHUNTER_API_URL. */
public final class ApiSpec {

    public static final String BASE_URL = resolveBaseUrl();

    static {
        // Print the full request and response, but only for requests whose assertions failed.
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    private ApiSpec() {
    }

    private static String resolveBaseUrl() {
        String fromProperty = System.getProperty("flakehunter.api.url");
        if (fromProperty != null && !fromProperty.isBlank() && !fromProperty.startsWith("${")) {
            return fromProperty;
        }
        String fromEnv = System.getenv("FLAKEHUNTER_API_URL");
        return fromEnv != null && !fromEnv.isBlank() ? fromEnv : "http://localhost:8080";
    }

    public static RequestSpecification json() {
        return new RequestSpecBuilder()
                .setBaseUri(BASE_URL)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }

    public static RequestSpecification authenticated(String apiKey) {
        return json().header("X-API-Key", apiKey);
    }
}
