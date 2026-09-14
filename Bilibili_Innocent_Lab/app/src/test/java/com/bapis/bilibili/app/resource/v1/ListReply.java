package com.bapis.bilibili.app.resource.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ListReply {
    private final List<PoolReply> pools;

    public ListReply(List<PoolReply> pools) {
        this.pools = Collections.unmodifiableList(new ArrayList<>(pools));
    }

    public List<PoolReply> getPoolsList() {
        return pools;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
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
