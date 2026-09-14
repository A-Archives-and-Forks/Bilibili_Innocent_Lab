package gr1;

import android.content.Context;
import tv.danmaku.bili.update.model.BiliUpgradeInfo;

/** 9.12.0 的更新检查包装层；与网络实现同签名，但不能作为 Hook 边界。 */
public final class a {
    private final c delegate = new c();

    public BiliUpgradeInfo a(Context context) {
        return delegate.a(context);
    }
}
