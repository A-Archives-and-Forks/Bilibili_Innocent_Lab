package com.bapis.bilibili.app.resource.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 形状照搬真实 `PoolReply`：`newBuilder(X)` 静态工厂给具体 builder，见 {@link MessageLiteStub}。 */
public final class PoolReply extends MessageLiteStub {
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

    /** 真实消息类带这个非泛型重载；解析器用它取元素类型，不依赖泛型签名。 */
    public ModuleReply getModules(int index) {
        return modules.get(index);
    }

    public static Builder newBuilder() {
        return new Builder(new PoolReply("", Collections.<ModuleReply>emptyList()));
    }

    public static Builder newBuilder(PoolReply source) {
        return new Builder(source);
    }

    @Override
    protected BuilderStub newConcreteBuilder() {
        return new Builder(this);
    }

    public static final class Builder extends BuilderStub {
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

        public Builder addModules(ModuleReply module) {
            modules.add(module);
            return this;
        }

        public Builder addModules(ModuleReply.Builder module) {
            modules.add(module.build());
            return this;
        }

        public PoolReply build() {
            return new PoolReply(poolName, modules);
        }
    }
}
