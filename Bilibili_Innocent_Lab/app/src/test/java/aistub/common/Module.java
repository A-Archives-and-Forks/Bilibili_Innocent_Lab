package aistub.common;

/** 真机是 oneof；替身只给拦截器读到的两个分支。 */
public class Module {
    private final UgcIntroduction ugcIntroduction;
    private final Relates relates;

    public Module(UgcIntroduction ugcIntroduction, Relates relates) {
        this.ugcIntroduction = ugcIntroduction;
        this.relates = relates;
    }

    public boolean hasUgcIntroduction() { return ugcIntroduction != null; }
    public UgcIntroduction getUgcIntroduction() { return ugcIntroduction; }
    public boolean hasRelates() { return relates != null; }
    public Relates getRelates() { return relates; }

    public static Builder newBuilder(Module original) { return new Builder(original); }

    public static final class Builder {
        private final UgcIntroduction ugcIntroduction;
        private Relates relates;

        Builder(Module original) {
            ugcIntroduction = original.ugcIntroduction;
            relates = original.relates;
        }

        public Builder setRelates(Relates value) { relates = value; return this; }
        public Module build() { return new Module(ugcIntroduction, relates); }
    }
}
