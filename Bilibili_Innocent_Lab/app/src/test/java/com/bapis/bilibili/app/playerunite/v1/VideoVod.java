package com.bapis.bilibili.app.playerunite.v1;

public final class VideoVod {
    private final CodeType preferCodecType;
    private final int fnval;

    public VideoVod(CodeType preferCodecType, int fnval) {
        this.preferCodecType = preferCodecType;
        this.fnval = fnval;
    }

    public CodeType getPreferCodecType() {
        return preferCodecType;
    }

    public int getFnval() {
        return fnval;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder newBuilder(VideoVod source) {
        return new Builder(source);
    }

    public static final class Builder {
        private CodeType preferCodecType;
        private int fnval;

        private Builder(VideoVod source) {
            preferCodecType = source.preferCodecType;
            fnval = source.fnval;
        }

        public Builder setPreferCodecType(CodeType value) {
            preferCodecType = value;
            return this;
        }

        public Builder setFnval(int value) {
            fnval = value;
            return this;
        }

        public VideoVod build() {
            return new VideoVod(preferCodecType, fnval);
        }
    }
}
