package io.kairo.code.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kairo-server.auth")
public class AuthProperties {

    private String inviteCode = "";
    private String jwtSecret = "";
    private int tokenExpiryHours = 24;

    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }

    public int getTokenExpiryHours() { return tokenExpiryHours; }
    public void setTokenExpiryHours(int tokenExpiryHours) { this.tokenExpiryHours = tokenExpiryHours; }

    public boolean requiresInviteCode() {
        return inviteCode != null && !inviteCode.isBlank();
    }
}
