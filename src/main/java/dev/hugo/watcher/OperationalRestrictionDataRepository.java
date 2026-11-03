package dev.hugo.watcher;

import org.springframework.data.repository.Repository;

import java.util.Optional;

/** Spring Data JDBC lookup. No save() to accidentally clear restrictive latches. */
public interface OperationalRestrictionDataRepository extends Repository<OperationalRestrictionRow, Integer> {
    Optional<OperationalRestrictionRow> findById(Integer singletonId);
}
