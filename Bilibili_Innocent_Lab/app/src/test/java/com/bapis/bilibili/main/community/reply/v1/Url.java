package com.bapis.bilibili.main.community.reply.v1;

/**
 * `Content.urls` 这张 map 的值类型；搜索链接的真正载体。
 *
 * 字段号取自 2026-09-15 的 `Reply/MainList` 抓包（wire 协议，跨版本稳定）：
 * title(1) = 被链接的关键词、prefix_icon(3) = 搜索小图标、
 * app_url_schema(4) = bilibili://search?from=appcommentline_search…、
 * pc_url(13) = 网页版地址。
 *
 * 这份替身只提供定位器要求的形状：无参、返回 String 的 getAppUrlSchema。
 * 故意也放上 getTitle / getPcUrl，用来证明定位器**只**选中那一个方法。
 */
public final class Url {
    public String getTitle() {
        return "关键词";
    }

    public String getAppUrlSchema() {
        return "bilibili://search?from=appcommentline_search";
    }

    public String getPcUrl() {
        return "//search.bilibili.com/all?from_source=webcommentline_search";
    }

    /** 带参重载：定位器要求 parameterCount == 0，这条必须被排除掉。 */
    public String getAppUrlSchema(int index) {
        return "";
    }
}
