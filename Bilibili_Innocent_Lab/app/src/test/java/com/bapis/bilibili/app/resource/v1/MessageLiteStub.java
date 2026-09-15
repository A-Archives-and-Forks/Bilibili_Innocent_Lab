package com.bapis.bilibili.app.resource.v1;

/**
 * 复刻 protobuf-lite 的**继承形状**，这是这组替身存在的全部意义。
 *
 * 2026-09-15 教训：原来的替身把 `toBuilder()` 写成返回**具体**的 `Builder`，
 * 于是解析器按 `toBuilder().returnType` 找 builder 也能通过——单测一路绿，
 * 真机却 `install=SKIPPED, reason=MISSING_HOST_STRUCTURE` 一次都没装上。
 *
 * 真实的 `GeneratedMessageLite.toBuilder()` 声明返回的是**基类**
 * `GeneratedMessageLite$Builder`（`ListReply` 自己并不重新声明它），
 * 所以 `build().returnType == replyClass` 这条自检必然不成立。
 * 具体 builder 只能从 `newBuilder(owner)` 这个**静态工厂**的返回类型上拿到。
 *
 * 这里照搬那个形状：`toBuilder()` 返回 [BuilderStub]，
 * 想拿具体 builder 必须走各消息类自己的 `newBuilder(...)`。
 * **别把返回类型改回具体类**——改了就等于把这次的真机故障从测试里抹掉。
 */
public abstract class MessageLiteStub {

    /** 对应 `GeneratedMessageLite$Builder`：只作为基类出现，不带任何具体消息的方法。 */
    public abstract static class BuilderStub {
    }

    /** 对应 `GeneratedMessageLite.toBuilder()`：声明返回基类。 */
    public final BuilderStub toBuilder() {
        return newConcreteBuilder();
    }

    protected abstract BuilderStub newConcreteBuilder();
}
