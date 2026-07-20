package org.egothor.methodatlas.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AzureOpenAiClient} endpoint-URL construction.
 */
class AzureOpenAiClientTest {

    private static final String BASE = "https://contoso.openai.azure.com";
    private static final String VERSION = "2024-02-01";

    @Test
    void buildEndpointUrl_simpleDeployment_isUnchanged() {
        assertEquals(
                BASE + "/openai/deployments/gpt-4o/chat/completions?api-version=" + VERSION,
                AzureOpenAiClient.buildEndpointUrl(BASE, "gpt-4o", VERSION));
    }

    @Test
    void buildEndpointUrl_deploymentWithSpace_encodedAsPercent20NotPlus() {
        String url = AzureOpenAiClient.buildEndpointUrl(BASE, "my deployment", VERSION);
        assertEquals(
                BASE + "/openai/deployments/my%20deployment/chat/completions?api-version=" + VERSION,
                url);
    }

    @Test
    void buildEndpointUrl_deploymentWithReservedChars_isEncoded() {
        // '/' and '?' would otherwise corrupt the path; they must be percent-encoded.
        String url = AzureOpenAiClient.buildEndpointUrl(BASE, "a/b?c", VERSION);
        assertEquals(
                BASE + "/openai/deployments/a%2Fb%3Fc/chat/completions?api-version=" + VERSION,
                url);
    }
}
