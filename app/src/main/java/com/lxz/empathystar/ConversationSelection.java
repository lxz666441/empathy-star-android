package com.lxz.empathystar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One visible frame only. No history, node objects, persistence or implicit context. */
final class ConversationSelection {
    static final int MAX_MESSAGES = 30, MAX_CHARACTERS = 12000;
    static final class Entry {
        final int id, side;
        final String text;
        final String speakerName;
        Entry(int id, String text, int side) { this(id, text, side, ""); }
        Entry(int id, String text, int side, String speakerName) {
            this.id = id; this.text = text; this.side = side; this.speakerName = speakerName;
        }
        String speaker() {
            if (!speakerName.isEmpty()) return "显示名：" + speakerName + "（非唯一身份）";
            return side == ChatSurfacePolicy.MESSAGE_SIDE_INCOMING ? "对方（未识别姓名）"
                : side == ChatSurfacePolicy.MESSAGE_SIDE_OUTGOING ? "自己" : "方向/说话人不明";
        }
    }
    final ChatSnapshot snapshot;
    final List<Entry> entries;
    final int filteredCount;
    final boolean group;
    private ConversationSelection(ChatSnapshot snapshot, List<Entry> entries, int filteredCount, boolean group) {
        this.snapshot = snapshot; this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.filteredCount = filteredCount;
        this.group = group;
    }
    static ConversationSelection from(ScreenReadResult result) {
        if (result == null || result.state != ScreenReadResult.State.READY)
            throw new IllegalArgumentException("SUMMARY_NO_READABLE_MESSAGES");
        List<Entry> safe = new ArrayList<>(); int filtered = 0;
        for (int i = 0; i < result.messages.size(); i++) {
            ScreenReadResult.Message message = result.messages.get(i);
            if (message.text.trim().isEmpty() || ChatContentPolicy.sensitive(message.text)) { filtered++; continue; }
            // Preserve occurrence IDs: equal strings from two messages remain distinct.
            safe.add(new Entry(i + 1, message.text, message.side, message.speakerName));
        }
        return new ConversationSelection(result.snapshot, safe, filtered,
            result.conversationType == ScreenReadResult.ConversationType.GROUP);
    }
    ConversationSelection select(Set<Integer> selected) {
        if (selected == null || selected.isEmpty()) throw new IllegalArgumentException("SUMMARY_EMPTY_SELECTION");
        Set<Integer> remaining = new HashSet<>(selected);
        List<Entry> chosen = new ArrayList<>(); int characters = 0;
        for (Entry entry : entries) if (remaining.remove(entry.id)) {
            if (ChatContentPolicy.sensitive(entry.text)) throw new IllegalArgumentException("SUMMARY_SENSITIVE_CONTENT");
            chosen.add(entry); characters += entry.text.length();
        }
        if (!remaining.isEmpty()) throw new IllegalArgumentException("SUMMARY_UNKNOWN_SELECTION");
        if (chosen.size() > MAX_MESSAGES || characters > MAX_CHARACTERS)
            throw new IllegalArgumentException("SUMMARY_SELECTION_TOO_LARGE");
        return new ConversationSelection(snapshot, chosen, filteredCount, group);
    }
    boolean belongsTo(ChatSnapshot current) {
        return current != null && snapshot.matches(current.packageName, current.windowId, current.revision);
    }
    int characters() { int count = 0; for (Entry entry : entries) count += entry.text.length(); return count; }
    Entry find(int id) { for (Entry entry : entries) if (entry.id == id) return entry; return null; }
}
