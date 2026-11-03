package dev.hugo.watcher.policy;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface GlobalPolicyDataRepository extends Repository<GlobalPolicyRow, Short> {
    @Query("select fields::text from global_policy where singleton_id=1")
    Optional<String> loadFields();
}
