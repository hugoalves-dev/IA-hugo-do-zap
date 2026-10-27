package dev.hugo.watcher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Allows one-shot administrative commands to start without scheduled processing or delivery. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "watcher.runtime", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class WatcherSchedulingConfiguration { }
