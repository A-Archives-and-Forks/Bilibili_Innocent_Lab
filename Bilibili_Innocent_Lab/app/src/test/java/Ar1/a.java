package ar1;

import android.content.Context;
import tv.danmaku.bili.update.model.BiliUpgradeInfo;

/** 同签名缓存包装层，不能被选成更新网络入口。 */
public final class a {
    public BiliUpgradeInfo a(Context context) { return new c().a(context); }
}
