package aistub.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Tab {
    private final List<TabModule> tabModules;

    public Tab(List<TabModule> tabModules) { this.tabModules = new ArrayList<>(tabModules); }

    public List<TabModule> getTabModuleList() { return Collections.unmodifiableList(tabModules); }

    public static Builder newBuilder(Tab original) { return new Builder(original); }

    public static final class Builder {
        private final List<TabModule> tabModules;

        Builder(Tab original) { tabModules = new ArrayList<>(original.tabModules); }

        public Builder clearTabModule() { tabModules.clear(); return this; }

        public Builder addAllTabModule(Iterable<? extends TabModule> values) {
            for (TabModule value : values) tabModules.add(value);
            return this;
        }

        public Tab build() { return new Tab(tabModules); }
    }
}
