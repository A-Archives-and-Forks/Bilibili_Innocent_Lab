package aistub.v1;

public class TabModule {
    private final IntroductionTab introduction;

    public TabModule(IntroductionTab introduction) { this.introduction = introduction; }

    public boolean hasIntroduction() { return introduction != null; }
    public IntroductionTab getIntroduction() { return introduction; }

    public static Builder newBuilder(TabModule original) { return new Builder(original); }

    public static final class Builder {
        private IntroductionTab introduction;

        Builder(TabModule original) { introduction = original.introduction; }

        public Builder setIntroduction(IntroductionTab value) { introduction = value; return this; }
        public TabModule build() { return new TabModule(introduction); }
    }
}
