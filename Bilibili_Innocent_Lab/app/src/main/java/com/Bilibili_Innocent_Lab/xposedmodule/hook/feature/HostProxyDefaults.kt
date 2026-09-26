package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 实现宿主接口的动态代理遇到**没处理的方法**时的返回值。
 *
 * 不能一律返回 null：接口方法若返回基本类型，代理返回 null 会在宿主调用处抛
 * `NullPointerException`——那是宿主自己的栈，框架的 PROTECTIVE 兜不住。
 */
internal fun hostProxyDefaultValue(returnType: Class<*>): Any? = when (returnType) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Character.TYPE -> '\u0000'
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    else -> null
}
