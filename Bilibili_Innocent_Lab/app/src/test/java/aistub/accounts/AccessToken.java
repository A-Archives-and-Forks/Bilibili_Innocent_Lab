package aistub.accounts;

/** `com.bilibili.lib.accounts.model.AccessToken` 的最小替身。 */
public class AccessToken {
    private final String accessKey;
    private final boolean valid;
    private final boolean expired;

    public AccessToken(String accessKey, boolean valid, boolean expired) {
        this.accessKey = accessKey;
        this.valid = valid;
        this.expired = expired;
    }

    public String getAccessKey() { return accessKey; }
    public boolean isValid() { return valid; }
    public boolean isExpired() { return expired; }
}
