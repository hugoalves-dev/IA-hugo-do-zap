package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void upgradesPromptGenerationDiagnosticsFromV60() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        String schema = "upgrade_prompt_generation_diagnostics";
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("60").load().migrate();
        jdbc.update("""
                insert into upgrade_prompt_generation_diagnostics.prompt_generation_jobs
                    (job_id,draft_id,expected_draft_version,actor_user_id,chat_id,operation,instructions,status,
                     created_at,started_at,completed_at)
                values ('50000000-0000-0000-0000-000000000060','60000000-0000-0000-0000-000000000060',
                        2,1001,2002,'REFINE','','FAILED',now(),now(),now())
                """);
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema=? and table_name='prompt_generation_jobs' and column_name='failure_stage'
                """, Integer.class, schema));

        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();

        assertEquals(1, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema=? and table_name='prompt_generation_jobs' and column_name='failure_stage'
                """, Integer.class, schema));
        assertEquals("NOT_ENQUEUED", jdbc.queryForObject("""
                select result_delivery_status from upgrade_prompt_generation_diagnostics.prompt_generation_jobs
                where job_id='50000000-0000-0000-0000-000000000060'
                """, String.class));
        assertEquals(0, jdbc.queryForObject("""
                select result_delivery_attempts from upgrade_prompt_generation_diagnostics.prompt_generation_jobs
                where job_id='50000000-0000-0000-0000-000000000060'
                """, Integer.class));
        assertDoesNotThrow(() -> jdbc.update("""
                insert into upgrade_prompt_generation_diagnostics.telegram_admin_audit
                    (action,outcome,actor_user_id,actor_chat_id,created_at)
                values ('prompt_generation','PROPOSED',1001,2002,now())
                """), "a generated draft is proposed, not applied to the active prompt");
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from upgrade_prompt_generation_diagnostics.telegram_admin_audit
                where action='prompt_generation' and outcome='PROPOSED'
                """, Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                update upgrade_prompt_generation_diagnostics.prompt_generation_jobs
                set failure_stage='PRIVATE',failure_code='PROVIDER_FAILURE'
                where job_id='50000000-0000-0000-0000-000000000060'
                """));
    }

    @Test void v59PreservesExistingPromptInputSessions() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_prompt_session").defaultSchema("upgrade_prompt_session")
                .target("58").load().migrate();
        jdbc.update("insert into upgrade_prompt_session.telegram_prompt_input_sessions(user_id,chat_id,expires_at,created_at) values (42,84,now()+interval '10 minutes',now())");
        Flyway.configure().dataSource(source).schemas("upgrade_prompt_session").defaultSchema("upgrade_prompt_session")
                .load().migrate();
        assertEquals(84L, jdbc.queryForObject("select chat_id from upgrade_prompt_session.telegram_prompt_input_sessions where user_id=42", Long.class));
        assertNull(jdbc.queryForObject("select draft_id from upgrade_prompt_session.telegram_prompt_input_sessions where user_id=42", java.util.UUID.class));
    }

    @Test void v58PreservesExistingPromptRevisionAndActiveHistory() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_existing_prompt").defaultSchema("upgrade_existing_prompt")
                .target("57").load().migrate();
        // The previous schema permitted a valid-looking hash supplied by a caller.
        jdbc.update("insert into upgrade_existing_prompt.classification_prompt_revisions(prompt_body,sha256,author_user_id,created_at) values (?,?,?,now())", "Legacy prompt", "a".repeat(64), 42);
        jdbc.update("update upgrade_existing_prompt.classification_prompt_state set active_revision=1,version=1,updated_by=42 where singleton_id=1");
        jdbc.update("insert into upgrade_existing_prompt.classification_prompt_state_history(version,previous_revision,next_revision,actor_user_id,changed_at) values (1,null,1,42,now())");
        Flyway.configure().dataSource(source).schemas("upgrade_existing_prompt").defaultSchema("upgrade_existing_prompt").load().migrate();
        assertEquals(1, jdbc.queryForObject("select active_revision from upgrade_existing_prompt.classification_prompt_state", Integer.class));
        assertEquals("Legacy prompt", jdbc.queryForObject("select prompt_body from upgrade_existing_prompt.classification_prompt_revisions where revision=1", String.class));
        assertEquals("manual", jdbc.queryForObject("select origin from upgrade_existing_prompt.classification_prompt_revisions where revision=1", String.class));
        assertEquals("activate", jdbc.queryForObject("select event from upgrade_existing_prompt.classification_prompt_state_history where version=1", String.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into upgrade_existing_prompt.classification_prompt_revisions(prompt_body,sha256,author_user_id,created_at) values (?,?,?,now())", "new", "b".repeat(64), 42));
    }

    @Test void promptMigrationsAddDefaultStateWithoutChangingExistingAdministration() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_prompt").defaultSchema("upgrade_prompt")
                .target("54").load().migrate();
        jdbc.update("update upgrade_prompt.archive_monitor_settings set mode='ARCHIVED_ONLY' where singleton_id=1");

        var flyway = Flyway.configure().dataSource(source).schemas("upgrade_prompt").defaultSchema("upgrade_prompt")
                .load();
        flyway.migrate();
        flyway.migrate();

        assertEquals(1, jdbc.queryForObject("select count(*) from upgrade_prompt.classification_prompt_state", Integer.class));
        assertNull(jdbc.queryForObject("select active_revision from upgrade_prompt.classification_prompt_state where singleton_id=1", Long.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from upgrade_prompt.classification_prompt_revisions", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from upgrade_prompt.classification_prompt_drafts", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from upgrade_prompt.classification_prompt_state_history", Integer.class));
        assertEquals("ARCHIVED_ONLY", jdbc.queryForObject(
                "select mode from upgrade_prompt.archive_monitor_settings where singleton_id=1", String.class));
    }

    @Test void addsRabbitPublicationMarkerWithoutChangingExistingPendingOutboxActions() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_rabbit").defaultSchema("upgrade_rabbit")
                .target("34").load().migrate();
        jdbc.update("""
                insert into upgrade_rabbit.outbox_actions(action_key,action_type,payload,pending_snapshot,status,
                    attempts,available_at,created_at,updated_at,sensitive_expires_at)
                values ('migration-action','archived_messages','{}'::jsonb,'[]'::jsonb,'PENDING',0,now(),now(),now(),now()+interval '1 day')
                """);

        Flyway.configure().dataSource(source).schemas("upgrade_rabbit").defaultSchema("upgrade_rabbit")
                .load().migrate();

        assertEquals(1, jdbc.queryForObject("""
                select count(*) from upgrade_rabbit.outbox_actions
                where action_key='migration-action' and status='PENDING' and rabbit_confirmed_at is null
                """, Integer.class));
    }

    @Test void addsLegacyRabbitJobRecoveryMarkerWithoutChangingPendingJobs() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_jobs").defaultSchema("upgrade_jobs")
                .target("35").load().migrate();
        jdbc.update("""
                insert into upgrade_jobs.inbox_events(source,event_type,external_id,payload)
                values ('evolution','messages.upsert','legacy-1','{}'::jsonb)
                """);
        jdbc.update("""
                insert into upgrade_jobs.processing_jobs(inbox_event_id,status)
                select id,'PENDING' from upgrade_jobs.inbox_events where external_id='legacy-1'
                """);

        Flyway.configure().dataSource(source).schemas("upgrade_jobs").defaultSchema("upgrade_jobs")
                .load().migrate();

        assertEquals(1, jdbc.queryForObject("""
                select count(*) from upgrade_jobs.processing_jobs job
                join upgrade_jobs.inbox_events inbox on inbox.id=job.inbox_event_id
                where inbox.external_id='legacy-1' and job.status='PENDING'
                  and job.rabbit_backlog_confirmed_at is null
                """, Integer.class));
    }

    @Test void addsTelegramBacklogRecoveryMarkerWithoutChangingPendingUpdates() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_telegram_rabbit").defaultSchema("upgrade_telegram_rabbit")
                .target("36").load().migrate();
        jdbc.update("""
                insert into upgrade_telegram_rabbit.telegram_webhook_inbox(update_id,payload)
                values (501,'{}'::jsonb)
                """);

        Flyway.configure().dataSource(source).schemas("upgrade_telegram_rabbit").defaultSchema("upgrade_telegram_rabbit")
                .load().migrate();

        assertEquals(1, jdbc.queryForObject("""
                select count(*) from upgrade_telegram_rabbit.telegram_webhook_inbox
                where update_id=501 and status='PENDING' and rabbit_backlog_confirmed_at is null
                """, Integer.class));
    }

    @Test void addsDurableCallbackFeedbackStateWithoutChangingPendingUpdates() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_callback_rabbit").defaultSchema("upgrade_callback_rabbit")
                .target("37").load().migrate();
        jdbc.update("""
                insert into upgrade_callback_rabbit.telegram_webhook_inbox(update_id,payload)
                values (601,'{}'::jsonb)
                """);

        Flyway.configure().dataSource(source).schemas("upgrade_callback_rabbit").defaultSchema("upgrade_callback_rabbit")
                .load().migrate();

        assertEquals(1, jdbc.queryForObject("""
                select count(*) from upgrade_callback_rabbit.telegram_webhook_inbox
                where update_id=601 and status='PENDING' and callback_id is null and callback_answered_at is null
                """, Integer.class));
    }

    @Test void upgradesExistingPendingRowsWithGenerationWithoutChangingPayload() throws Exception {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_generation").defaultSchema("upgrade_generation")
                .target("9").load().migrate();
        jdbc.update("insert into upgrade_generation.pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values (?,?::jsonb,now(),now(),now()+interval '1 day')",
                "fixture-upgrade@s.whatsapp.net", "{\"messageCount\":2}");
        Flyway.configure().dataSource(source).schemas("upgrade_generation").defaultSchema("upgrade_generation")
                .load().migrate();
        assertNotNull(jdbc.queryForObject("select generation from upgrade_generation.pending_notification", java.util.UUID.class));
        assertEquals("2", jdbc.queryForObject("select payload->>'messageCount' from upgrade_generation.pending_notification", String.class));
    }

    @Test void flywayIsRepeatableAndArchivedRemainsTriState() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load();
        flyway.migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("insert into chat_snapshot (chat_jid, archived) values (?, null)")) {
            statement.setString(1, "120363000000000000@g.us");
            statement.executeUpdate();
        }

        flyway.migrate();

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("select archived from chat_snapshot where chat_jid = '120363000000000000@g.us'")) {
            assertEquals(true, result.next());
            assertNull(result.getObject("archived"));
            assertEquals(false, result.next());
        }
    }

    @Test void upgradesV12WithoutChangingScopeOrTelegramSettingsAndSeedsLegacyProfiles() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_policy").defaultSchema("upgrade_policy")
                .target("12").load().migrate();
        jdbc.update("update upgrade_policy.archive_monitor_settings set mode='ARCHIVED_ONLY' where singleton_id=1");
        var telegramNextAllowed = java.sql.Timestamp.from(java.time.Instant.parse("2026-10-27T15:00:00Z"));
        jdbc.update("update upgrade_policy.telegram_delivery_rate_limit set next_allowed_at=? where singleton_id=1", telegramNextAllowed);

        Flyway.configure().dataSource(source).schemas("upgrade_policy").defaultSchema("upgrade_policy")
                .load().migrate();

        assertEquals("ARCHIVED_ONLY", jdbc.queryForObject(
                "select mode from upgrade_policy.archive_monitor_settings where singleton_id=1", String.class));
        assertEquals(telegramNextAllowed, jdbc.queryForObject(
                "select next_allowed_at from upgrade_policy.telegram_delivery_rate_limit where singleton_id=1", java.sql.Timestamp.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from upgrade_policy.policy_profiles", Integer.class));
        assertEquals("WORK_WINDOW", jdbc.queryForObject(
                "select fields->>'mode' from upgrade_policy.policy_profiles where profile_name='WORK_ONLY'", String.class));
        assertEquals("ANYTIME", jdbc.queryForObject(
                "select fields->>'mode' from upgrade_policy.policy_profiles where profile_name='ANYTIME'", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from upgrade_policy.holiday_calendar", Integer.class));
    }

    @Test void holidayValidationRequiresRecordedManualAttestation() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("holiday_attestation").defaultSchema("holiday_attestation")
                .target("13").load().migrate();
        jdbc.update("""
                insert into holiday_attestation.holiday_calendar(local_date,jurisdiction,name,source,calendar_year,validation_status)
                values ('2026-10-27','LINS','legacy fixture','https://official.example/2026',2026,'VALIDATED')
                """);
        Flyway.configure().dataSource(source).schemas("holiday_attestation").defaultSchema("holiday_attestation")
                .load().migrate();
        assertEquals("UNKNOWN", jdbc.queryForObject("""
                select validation_status from holiday_attestation.holiday_calendar where name='legacy fixture'
                """, String.class));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into holiday_attestation.holiday_calendar(local_date,jurisdiction,name,source,calendar_year,validation_status)
                values ('2026-10-27','LINS','unattested fixture','https://official.example/2026',2026,'VALIDATED')
                """));

        jdbc.update("""
                insert into holiday_attestation.holiday_calendar(local_date,jurisdiction,name,source,calendar_year,
                    validation_status,validated_by,validated_at)
                values ('2026-10-27','LINS','fixture','https://official.example/2026',2026,'VALIDATED',?,?)
                """, "fixture-maintainer", java.sql.Timestamp.from(java.time.Instant.parse("2026-10-27T12:00:00Z")));
        assertEquals("fixture-maintainer", jdbc.queryForObject(
                "select validated_by from holiday_attestation.holiday_calendar where name='fixture'", String.class));
    }

    @Test void v17DisablesUndisclosedLegacyTemplatesWithoutRewritingApprovedText() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("template_disclosure_upgrade").defaultSchema("template_disclosure_upgrade")
                .target("16").load().migrate();
        jdbc.update("""
                insert into template_disclosure_upgrade.response_templates
                    (template_id,version,situation,body,identifies_automation,enabled)
                values ('legacy_approved',1,'CONTACT_REQUEST','Oi, pode deixar.',true,true)
                """);

        jdbc.update("""
                insert into template_disclosure_upgrade.response_templates
                    (template_id,version,situation,body,identifies_automation,enabled)
                values ('already_disclosed',1,'CONTACT_REQUEST','Resposta automática: tudo bem.',true,true)
                """);

        Flyway.configure().dataSource(source).schemas("template_disclosure_upgrade").defaultSchema("template_disclosure_upgrade")
                .load().migrate();

        assertEquals(true, jdbc.queryForObject("""
                select enabled from template_disclosure_upgrade.response_templates
                where template_id='already_disclosed' and version=1
                """, Boolean.class));
        assertEquals("Resposta automática: tudo bem.", jdbc.queryForObject("""
                select body from template_disclosure_upgrade.response_templates
                where template_id='already_disclosed' and version=1
                """, String.class));
        assertEquals("Oi, pode deixar.", jdbc.queryForObject("""
                select body from template_disclosure_upgrade.response_templates
                 where template_id='legacy_approved' and version=1
                """, String.class));
        assertEquals(false, jdbc.queryForObject("""
                select enabled from template_disclosure_upgrade.response_templates
                 where template_id='legacy_approved' and version=1
                """, Boolean.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                update template_disclosure_upgrade.response_templates set enabled=true
                 where template_id='legacy_approved' and version=1
                """));
        jdbc.update("""
                update template_disclosure_upgrade.response_templates
                   set body='Resposta automática: Oi, pode deixar.', enabled=true
                 where template_id='legacy_approved' and version=1
                """);
        assertEquals(true, jdbc.queryForObject("""
                select enabled from template_disclosure_upgrade.response_templates
                 where template_id='legacy_approved' and version=1
                """, Boolean.class));
        assertEquals("Esta é uma resposta automática. Vou retornar assim que possível.", jdbc.queryForObject("""
                select body from template_disclosure_upgrade.response_templates
                 where template_id='contact_request' and version=1
                """, String.class));
    }
}
