package dev.hugo.watcher.policy;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatPolicyOverrideDataRepository extends Repository<ChatPolicyOverrideRow, String> {
    /**
     * Preserve DISTINCT over (profile_name, fields), not chat_jid:
     * same policies on multiple aliases are equivalent, conflicting policies fail closed.
     */
    @Query(value = """
            select distinct profile_name, fields::text as fields
              from chat_policy_overrides
             where chat_jid=:chat
                or chat_jid in (select alias from identity_aliases where canonical_identity=:canonical)
            """, rowMapperClass = PolicyOverrideFieldsRowMapper.class)
    List<PolicyOverrideFields> forCanonicalChat(@Param("chat") String chat,
                                                @Param("canonical") String canonical);
}
