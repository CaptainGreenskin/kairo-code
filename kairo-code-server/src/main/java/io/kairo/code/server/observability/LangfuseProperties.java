package io.kairo.code.server.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized config for the Langfuse OTLP exporter.
 *
 * <p>Bound from {@code langfuse.*} in {@code application.properties}. The secret key should
 * normally come from the {@code LANGFUSE_SECRET_KEY} env var rather than being committed —
 * the property file references the env via {@code ${LANGFUSE_SECRET_KEY:fallback}} so local
 * dev still works without setting it.
 */
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    /** Master switch. When false, the {@link LangfuseConfig} bean is skipped. */
    private boolean enabled = false;

    /** Langfuse base URL, e.g. {@code https://langfuse.alibaba-inc.com}. */
    private String host = "";

    /** Public key (pk-lf-…). Safe to commit. */
    private String publicKey = "";

    /** Secret key (sk-lf-…). Should come from env. */
    private String secretKey = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}
