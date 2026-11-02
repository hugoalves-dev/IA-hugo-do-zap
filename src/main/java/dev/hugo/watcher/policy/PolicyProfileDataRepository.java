package dev.hugo.watcher.policy;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PolicyProfileDataRepository extends Repository<PolicyProfileRow, String> {
    @Query("select fields::text from policy_profiles where profile_name=:profileName")
    Optional<String> loadFields(@Param("profileName") String profileName);
}
