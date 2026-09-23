package kotlinx.serialization.descriptors;

/** 测试夹具：kotlinx 描述符的三个只读访问器。 */
public interface SerialDescriptor {
    String getSerialName();

    int getElementsCount();

    String getElementName(int index);
}
