package com.bapis.bilibili.app.resource.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 形状照搬真实 `ListReply`：`newBuilder(X)` 静态工厂给具体 builder，见 {@link MessageLiteStub}。 */
public final class ListReply extends MessageLiteStub {
    private final List<PoolReply> pools;

    public ListReply(List<PoolReply> pools) {
        this.pools = Collections.unmodifiableList(new ArrayList<>(pools));
    }

    public List<PoolReply> getPoolsList() {
        return pools;
    }

    /** 真实消息类带这个非泛型重载；解析器用它取元素类型，不依赖泛型签名。 */
    public PoolReply getPools(int index) {
        return pools.get(index);
    }

    public static Builder newBuilder() {
        return new Builder(new ListReply(Collections.<PoolReply>emptyList()));
    }

    public static Builder newBuilder(ListReply source) {
        return new Builder(source);
    }

    @Override
    protected BuilderStub newConcreteBuilder() {
        return new Builder(this);
    }

    public static final class Builder extends BuilderStub {
        private final List<PoolReply> pools;

        private Builder(ListReply source) {
            pools = new ArrayList<>(source.pools);
        }

        public Builder clearPools() {
            pools.clear();
            return this;
        }

        public Builder addPools(PoolReply pool) {
            pools.add(pool);
            return this;
        }

        public Builder addPools(PoolReply.Builder pool) {
            pools.add(pool.build());
            return this;
        }

        public ListReply build() {
            return new ListReply(pools);
        }
    }
}
