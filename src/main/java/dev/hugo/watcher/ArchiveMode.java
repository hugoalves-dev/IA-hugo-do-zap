package dev.hugo.watcher;

public enum ArchiveMode {
    ALL,
    ARCHIVED_ONLY,
    UNARCHIVED_ONLY;

    public boolean accepts(Boolean archived, boolean synchronizedSnapshot) {
        return switch (this) {
            case ALL -> true;
            case ARCHIVED_ONLY -> synchronizedSnapshot && Boolean.TRUE.equals(archived);
            case UNARCHIVED_ONLY -> synchronizedSnapshot && Boolean.FALSE.equals(archived);
        };
    }
}
