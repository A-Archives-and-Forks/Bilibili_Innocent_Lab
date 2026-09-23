package fm1;

import kotlinx.serialization.KSerializer;
import kotlinx.serialization.descriptors.SerialDescriptor;

/**
 * 9.10.0(9100200) `HomeTabItemData` 夹具（真机混淆名 `fm1.n`，每版都换名）。
 *
 * <p>字段名按 R8 的声明顺序单字母分配，元素顺序与 dex 字段顺序一致——
 * 这是定位器按"字段名排序 = 元素顺序"映射的前提，由离线审计在 20 个宿主上实证。
 * 字段 {@code b} 与内部类 {@code b} 同名也是照抄 R8 的产物。
 */
public final class n {
    public static final b Companion = new b();

    public final String a;
    public final String b;
    public final String c;
    public final String d;
    public final String e;
    public final int f;
    public final int g;
    public final String h;

    public n(String id, String name, String uri, String icon, String iconSelected, int defaultSelected, int pos, String tabId) {
        this.a = id;
        this.b = name;
        this.c = uri;
        this.d = icon;
        this.e = iconSelected;
        this.f = defaultSelected;
        this.g = pos;
        this.h = tabId;
    }

    /** 反序列化构造器形状：{@code (int seen, 元素…)}。 */
    public n(int seen, String id, String name, String uri, String icon, String iconSelected, int defaultSelected, int pos, String tabId) {
        this(id, name, uri, icon, iconSelected, defaultSelected, pos, tabId);
    }

    public static final class b {
        public KSerializer<n> serializer() {
            return Serializer.INSTANCE;
        }
    }

    /** 真机是 `n$a`；夹具改名只为避开 Java 对同名字段的遮蔽，定位器不看这个名字。 */
    public static final class Serializer implements KSerializer<n> {
        static final Serializer INSTANCE = new Serializer();

        @Override
        public SerialDescriptor getDescriptor() {
            return new FixtureDescriptor(
                    "tv.danmaku.bili.khomeapi.frame.HomeTabItemData",
                    "id", "name", "uri", "icon", "icon_selected", "default_selected", "pos", "tab_id");
        }
    }
}
