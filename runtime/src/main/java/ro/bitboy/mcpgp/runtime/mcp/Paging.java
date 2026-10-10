package ro.bitboy.mcpgp.runtime.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The paged envelope of tools-contract §10: {@code items, pageSize, position, hasMore, nextCursor}. */
final class Paging {

    private Paging() {
    }

    /** @param cursor the position to start from (as given by {@code nextCursor}); null for the first page */
    static Map<String, Object> page(List<?> all, Object cursor, int size) {
        int position = 0;
        if (cursor != null && !cursor.toString().isBlank()) {
            try {
                position = Math.max(0, Integer.parseInt(cursor.toString()));
            } catch (NumberFormatException e) {
                throw new ToolError("INPUT_VALIDATION", "cursor must be a value from nextCursor");
            }
        }
        int end = Math.min(all.size(), position + size);
        List<?> items = position >= all.size() ? List.of() : all.subList(position, end);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("items", items);
        envelope.put("pageSize", items.size());
        envelope.put("position", position);
        envelope.put("hasMore", end < all.size());
        envelope.put("nextCursor", end < all.size() ? String.valueOf(end) : null);
        return envelope;
    }
}
