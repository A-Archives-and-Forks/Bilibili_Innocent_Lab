package com.bapis.bilibili.app.resource.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PoolReply {
    private final String poolName;
    private final List<ModuleReply> modules;

    public PoolReply(String poolName, List<ModuleReply> modules) {
        this.poolName = poolName;
        this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
    }

    public String getPoolName() {
        return poolName;
    }

    public List<ModuleReply> getModulesList() {
        return modules;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private String poolName;
        private List<ModuleReply> modules;

        private Builder(PoolReply source) {
            poolName = source.poolName;
            modules = new ArrayList<>(source.modules);
        }

        public Builder clearModules() {
            modules.clear();
            return this;
        }

        public PoolReply build() {
            return new PoolReply(poolName, modules);
        }
    }
}
