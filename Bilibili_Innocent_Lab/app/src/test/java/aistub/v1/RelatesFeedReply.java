package aistub.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** `viewunite.v1.RelatesFeedReply` 的最小替身：不可变列表 + 混淆名 Builder，与真 protobuf 同形。 */
public class RelatesFeedReply {
    private static final RelatesFeedReply DEFAULT = new RelatesFeedReply(Collections.emptyList(), "");

    private final List<Object> relates;
    private final String offset;

    public RelatesFeedReply(List<?> relates, String offset) {
        this.relates = Collections.unmodifiableList(new ArrayList<Object>(relates));
        this.offset = offset;
    }

    public List<Object> getRelatesList() { return relates; }
    public String getOffset() { return offset; }

    public static RelatesFeedReply getDefaultInstance() { return DEFAULT; }

    public static b newBuilder(RelatesFeedReply original) { return new b(original); }

    public static final class b {
        private final List<Object> relates;
        private final String offset;

        b(RelatesFeedReply original) {
            relates = new ArrayList<Object>(original.relates);
            offset = original.offset;
        }

        public b clearRelates() { relates.clear(); return this; }
        public b addAllRelates(Iterable<?> values) { for (Object v : values) relates.add(v); return this; }
        public RelatesFeedReply build() { return new RelatesFeedReply(relates, offset); }
    }
}
