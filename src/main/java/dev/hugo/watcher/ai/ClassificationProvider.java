package dev.hugo.watcher.ai;

public interface ClassificationProvider {
    String name();
    ClassificationResult classify(ClassificationInput input);
}
