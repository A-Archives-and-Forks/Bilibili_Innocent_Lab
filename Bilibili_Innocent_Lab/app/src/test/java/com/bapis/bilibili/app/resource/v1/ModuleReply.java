package com.bapis.bilibili.app.resource.v1;

public final class ModuleReply {
    private final String moduleName;

    public ModuleReply(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getModuleName() {
        return moduleName;
    }

    public static final class Builder {
        private String moduleName;

        public Builder setModuleName(String value) {
            moduleName = value;
            return this;
        }

        public ModuleReply build() {
            return new ModuleReply(moduleName);
        }
    }
}
