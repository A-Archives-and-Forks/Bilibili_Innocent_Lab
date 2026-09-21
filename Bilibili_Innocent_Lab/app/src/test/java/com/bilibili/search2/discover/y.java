package com.bilibili.search2.discover;

import com.bilibili.search2.api.SearchReferral;
import com.bilibili.search2.api.SearchSquareType;

import java.util.List;

/**
 * 匿名投递宿主，对应真机 8.84.0–8.96.0 的宿主形态。
 *
 * <p>宿主把投递回调写成页面 ViewModel 的匿名内部类，R8 重命名后二进制名是
 * {@code com.bilibili.search2.discover.r$a}；在 JVM 上同一形态编译为
 * {@code com.bilibili.search2.discover.y$1}。
 *
 * <p>两者都不会出现在 {@code Class.getDeclaredClasses()} 中：Android 平台上不存在
 * {@code dalvik.annotation.InnerClasses} 注解，匿名类也不进入外层类的
 * {@code MemberClasses} 注解，因此只能按 {@code Outer$Suffix} 有界枚举后再加载。
 *
 * <p>这里用 Java 而不是 Kotlin 声明，是为了让匿名类的二进制名确定为 {@code y$1}：
 * Kotlin 的匿名对象可能带上属性名前缀，无法锁住「数字后缀」这条枚举路径。
 */
public final class y {

    public final r model = new r();

    /** 匿名类，JVM 名 {@code com.bilibili.search2.discover.y$1}。 */
    public final Callback callback = new Callback() {

        /** 投递宿主持有的分区载体，供 {@code delivery()} 按结构定位。 */
        public final r owner = model;

        @Override
        public List<History> getHistoryList() {
            return owner.getHistory();
        }

        @Override
        public void f(List<SearchSquareType> values) {
            owner.sections = values;
        }

        @Override
        public void b(List<SearchReferral.Guess> values) {
            owner.recommendation.setValue(new g(values, "", null));
        }
    };
}
