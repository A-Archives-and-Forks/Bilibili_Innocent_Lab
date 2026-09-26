package aistub.accounts;

import android.content.Context;

/** `com.bilibili.lib.accounts.BiliAccounts` 的最小替身。 */
public class BiliAccounts {
    public static AccessToken current;
    private static final BiliAccounts INSTANCE = new BiliAccounts();

    public static BiliAccounts get(Context context) { return INSTANCE; }

    public AccessToken getAccessToken() { return current; }
}
