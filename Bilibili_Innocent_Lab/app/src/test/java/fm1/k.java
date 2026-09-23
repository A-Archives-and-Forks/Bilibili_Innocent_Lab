package fm1;

import java.util.List;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.descriptors.SerialDescriptor;

/**
 * 9.10.0(9100200) `HomeTabData` 夹具（真机混淆名 `fm1.k`）。
 *
 * <p>三种构造器都照抄：主构造器、直接写字段的反序列化构造器 {@code (int, 元素…)}、
 * 被 R8 压缩掉末尾参数、委托给主构造器的默认参数构造器 {@code (int mask, List×4)}。
 */
public final class k {
    public static final b Companion = new b();

    public final List<n> a;
    public final List<n> b;
    public final List<n> c;
    public final List<n> d;
    public final q e;

    public k(List<n> top, List<n> tab, List<n> bottom, List<n> topMore, q topLeft) {
        this.a = top;
        this.b = tab;
        this.c = bottom;
        this.d = topMore;
        this.e = topLeft;
    }

    public k(int seen, List<n> top, List<n> tab, List<n> bottom, List<n> topMore, q topLeft) {
        this.a = top;
        this.b = tab;
        this.c = bottom;
        this.d = topMore;
        this.e = topLeft;
    }

    public k(int mask, List<n> top, List<n> tab, List<n> bottom, List<n> topMore) {
        this(top, tab, bottom, topMore, null);
    }

    public static final class b {
        public KSerializer<k> serializer() {
            return Serializer.INSTANCE;
        }
    }

    /** 真机是 `k$a`；夹具改名只为避开 Java 对同名字段的遮蔽，定位器不看这个名字。 */
    public static final class Serializer implements KSerializer<k> {
        static final Serializer INSTANCE = new Serializer();

        @Override
        public SerialDescriptor getDescriptor() {
            return new FixtureDescriptor(
                    "tv.danmaku.bili.khomeapi.frame.HomeTabData",
                    "top", "tab", "bottom", "top_more", "top_left");
        }
    }
}
