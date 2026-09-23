package fm1;

import kotlinx.serialization.descriptors.SerialDescriptor;

/** 固定元素表的描述符夹具。 */
final class FixtureDescriptor implements SerialDescriptor {
    private final String serialName;
    private final String[] elements;

    FixtureDescriptor(String serialName, String... elements) {
        this.serialName = serialName;
        this.elements = elements;
    }

    @Override
    public String getSerialName() {
        return serialName;
    }

    @Override
    public int getElementsCount() {
        return elements.length;
    }

    @Override
    public String getElementName(int index) {
        return elements[index];
    }
}
