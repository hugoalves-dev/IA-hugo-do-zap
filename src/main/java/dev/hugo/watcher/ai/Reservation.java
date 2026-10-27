package dev.hugo.watcher.ai;

import java.time.LocalDate;
import java.util.UUID;

public record Reservation(UUID id, long estimatedMicros, LocalDate usageDay, LocalDate usageMonth) { }
