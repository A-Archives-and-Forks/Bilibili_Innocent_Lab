package aistub.v1;

public class ECodeConfig {
    private static final ECodeConfig DEFAULT = new ECodeConfig("", "");

    private final String redirectUrl;
    private final String msg;

    private ECodeConfig(String redirectUrl, String msg) {
        this.redirectUrl = redirectUrl;
        this.msg = msg;
    }

    public String getRedirectUrl() { return redirectUrl; }
    public String getMsg() { return msg; }

    public static ECodeConfig getDefaultInstance() { return DEFAULT; }

    public static Builder newBuilder(ECodeConfig original) { return new Builder(original); }

    public static final class Builder {
        private String redirectUrl;
        private String msg;

        Builder(ECodeConfig original) {
            redirectUrl = original.redirectUrl;
            msg = original.msg;
        }

        public Builder setRedirectUrl(String value) { redirectUrl = value; return this; }
        public Builder setMsg(String value) { msg = value; return this; }
        public ECodeConfig build() { return new ECodeConfig(redirectUrl, msg); }
    }
}
