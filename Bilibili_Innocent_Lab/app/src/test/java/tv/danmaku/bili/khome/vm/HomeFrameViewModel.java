package tv.danmaku.bili.khome.vm;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 新首页框架 ViewModel 夹具（宿主里类名未混淆）。
 *
 * <p>真机字段是 {@code StateFlow<HomeFrameState>} / {@code Flow<List<HomeTabItemData>>}；
 * 定位器只看泛型参数，容器类型换成 JDK 自带的 AtomicReference 不影响判定。
 */
public class HomeFrameViewModel {
    public final AtomicReference<Zl1.a> c = new AtomicReference<>();
    public final AtomicReference<List<fm1.n>> e = new AtomicReference<>();
}
