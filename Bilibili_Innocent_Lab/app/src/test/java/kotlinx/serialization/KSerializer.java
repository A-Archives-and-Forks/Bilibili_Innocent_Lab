package kotlinx.serialization;

import kotlinx.serialization.descriptors.SerialDescriptor;

/** 测试夹具：只保留定位器用到的描述符访问器（宿主里该接口名与访问器名均未混淆）。 */
public interface KSerializer<T> {
    SerialDescriptor getDescriptor();
}
