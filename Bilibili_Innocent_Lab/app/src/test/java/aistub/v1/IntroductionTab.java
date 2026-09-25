package aistub.v1;

import aistub.common.Module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IntroductionTab {
    private final List<Module> modules;

    public IntroductionTab(List<Module> modules) { this.modules = new ArrayList<>(modules); }

    public List<Module> getModulesList() { return Collections.unmodifiableList(modules); }

    public static Builder newBuilder(IntroductionTab original) { return new Builder(original); }

    public static final class Builder {
        private final List<Module> modules;

        Builder(IntroductionTab original) { modules = new ArrayList<>(original.modules); }

        public Builder clearModules() { modules.clear(); return this; }

        public Builder addAllModules(Iterable<? extends Module> values) {
            for (Module value : values) modules.add(value);
            return this;
        }

        public IntroductionTab build() { return new IntroductionTab(modules); }
    }
}
