package aistub.v1;

import aistub.common.Owner;

/**
 * JVM 替身：形状照 {@code viewunite.v1.ViewReply}，只为 AI 声明拦截的单测所用。
 *
 * <p>独立包是刻意的：共享的 {@code com.bapis} 替身被 VersionAdapter 的结构定位测试依赖，
 * 往里加 getter 会改变它们的唯一性判定。
 */
public class ViewReply {
    private static final ViewReply DEFAULT = new ViewReply(null, null, null);

    private final Arc arc;
    private final Owner owner;
    private final Tab tab;
    private final int ecode;
    private final ECodeConfig ecodeConfig;

    public ViewReply(Arc arc, Owner owner, Tab tab) {
        this(arc, owner, tab, 0, null);
    }

    private ViewReply(Arc arc, Owner owner, Tab tab, int ecode, ECodeConfig ecodeConfig) {
        this.arc = arc;
        this.owner = owner;
        this.tab = tab;
        this.ecode = ecode;
        this.ecodeConfig = ecodeConfig;
    }

    public boolean hasArc() { return arc != null; }
    public Arc getArc() { return arc; }
    public boolean hasOwner() { return owner != null; }
    public Owner getOwner() { return owner; }
    public boolean hasTab() { return tab != null; }
    public Tab getTab() { return tab; }
    public int getEcodeValue() { return ecode; }

    public ECodeConfig getEcodeConfig() {
        return ecodeConfig == null ? ECodeConfig.getDefaultInstance() : ecodeConfig;
    }

    public static ViewReply getDefaultInstance() { return DEFAULT; }

    public static Builder newBuilder(ViewReply original) { return new Builder(original); }

    public static final class Builder {
        private final Arc arc;
        private final Owner owner;
        private Tab tab;
        private int ecode;
        private ECodeConfig ecodeConfig;

        Builder(ViewReply original) {
            arc = original.arc;
            owner = original.owner;
            tab = original.tab;
            ecode = original.ecode;
            ecodeConfig = original.ecodeConfig;
        }

        public Builder setTab(Tab value) { tab = value; return this; }
        public Builder setEcodeValue(int value) { ecode = value; return this; }
        public Builder setEcodeConfig(ECodeConfig value) { ecodeConfig = value; return this; }
        public ViewReply build() { return new ViewReply(arc, owner, tab, ecode, ecodeConfig); }
    }
}
