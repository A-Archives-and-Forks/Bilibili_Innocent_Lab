package com.bilibili.search2.discover;

import com.bilibili.search2.api.SearchReferral;
import com.bilibili.search2.api.SearchSquareType;

import java.util.List;

/**
 * 投递回调的方法与字段形状，供 {@link y} / {@link z} 两个 fixture 共用。
 *
 * <p>接口自身不是候选：包内只有单字母根类及其嵌套类型会进入候选集，
 * 且候选集在收集阶段就会过滤掉接口与抽象类。
 */
interface Callback {

    List<History> getHistoryList();

    void f(List<SearchSquareType> values);

    void b(List<SearchReferral.Guess> values);
}
