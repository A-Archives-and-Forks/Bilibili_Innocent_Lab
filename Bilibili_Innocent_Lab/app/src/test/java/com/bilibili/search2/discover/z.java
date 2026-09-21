package com.bilibili.search2.discover;

import com.bilibili.search2.api.SearchReferral;
import com.bilibili.search2.api.SearchSquareType;

import java.util.List;

/**
 * 命名成员类形态：JVM 名 {@code com.bilibili.search2.discover.z$a}，与真机 R8 重命名后的
 * {@code r$a} 同名同后缀。
 *
 * <p>与 {@link y} 的区别在于命名成员类会进入外层类的 {@code MemberClasses}，因此
 * {@code declaredClasses} 与后缀枚举两条路径都能拿到它。该 fixture 锁的是候选去重：
 * 同一个 {@code Class} 出现两次会让 {@code singleOrNull()} 把唯一候选误判成候选歧义。
 */
public final class z {

    private z() {
    }

    public static final class a implements Callback {

        public final r owner;

        public a(r owner) {
            this.owner = owner;
        }

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
    }
}
