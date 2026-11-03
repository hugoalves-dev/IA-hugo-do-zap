package dev.hugo.watcher.policy;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public final class PolicyOverrideFieldsRowMapper implements RowMapper<PolicyOverrideFields> {
    @Override
    public PolicyOverrideFields mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PolicyOverrideFields(rs.getString("profile_name"), rs.getString("fields"));
    }
}
