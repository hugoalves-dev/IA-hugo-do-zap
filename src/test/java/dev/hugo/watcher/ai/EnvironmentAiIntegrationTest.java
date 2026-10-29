package dev.hugo.watcher.ai;

import dev.hugo.watcher.*;
import dev.hugo.watcher.simulation.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class EnvironmentAiIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate jdbc; DataSourceTransactionManager tx; BudgetService budgets;
    Clock clock = Clock.systemUTC(); AtomicInteger calls;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source); tx = new DataSourceTransactionManager(source);
        budgets = new BudgetService(jdbc, tx, new SimpleMeterRegistry()); calls = new AtomicInteger();
        jdbc.update("update archive_monitor_settings set mode='ALL'");
        jdbc.update("update automation_settings set ai_classification_enabled=false,kill_switch_enabled=true");
        jdbc.update("update telegram_admin_settings set simulation_enabled=false");
    }
    private AiClassificationProperties config(boolean bypass, boolean consent) {
        return new AiClassificationProperties(true,null,null,"fake","model",10,Duration.ofSeconds(1),null,true,bypass,consent,false);
    }
    private dev.hugo.watcher.OperationalState state(AiClassificationProperties config, boolean kill) {
        var flags = new AutomationProperties(); flags.setKillSwitchEnabled(kill);
        return new OperationalState(jdbc, flags, config, new WatcherSimulationProperties(), false);
    }
    private AiSimulationClassifier classifier(AiClassificationProperties config, boolean kill) {
        ProviderTransport fake = (request, timeout, max) -> {
            calls.incrementAndGet();
            return validResponse();
        };
        var classifier = new AiSimulationClassifier(jdbc, budgets, clock, config, fake);
        classifier.configureOperationalState(state(config, kill)); return classifier;
    }
    private ProviderTransport.Response validResponse() {
        return new ProviderTransport.Response(200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"CONTACT_REQUEST\\\",\\\"requestDetected\\\":true,\\\"urgency\\\":\\\"NORMAL\\\",\\\"evidence\\\":[],\\\"confidence\\\":0.9}\"}}]}");
    }
    private Batch batch(Map<String,Object> content) {
        Instant now=clock.instant(); var debounce=new DebounceService(jdbc,new WatcherSimulationProperties());
        debounce.enqueue(new IncomingMessage(Optional.of(UUID.randomUUID().toString()),"test@s.whatsapp.net",Optional.empty(),Optional.empty(),Optional.empty(),false,"notify",false,content,now),now);
        return debounce.claimReady(now.plusSeconds(60)).getFirst();
    }
    @Test void optionalControlsAllowShadowDespiteContradictoryLegacyValues() {
        var config=config(true,false); var ai=classifier(config,false);
        var service=new SimulationService(jdbc,tx,clock,new WatcherSimulationProperties(),new DecisionEngine(),
                new dev.hugo.watcher.policy.PersistedPolicyRepository(jdbc,new dev.hugo.watcher.policy.PolicyResolver()),
                new dev.hugo.watcher.policy.AvailabilityService(new dev.hugo.watcher.policy.HolidayService(jdbc)),new WatcherProperties(),ai);
        service.configureOperationalState(state(config,false));
        var b=batch(Map.of("conversation","Preciso falar com você")); service.evaluate(b);
        assertEquals(1,calls.get());
        assertEquals("CLASSIFIED",jdbc.queryForObject("select classification_status from simulation_decisions where batch_id=?",String.class,b.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from ai_budget_reservations",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from whatsapp_action_outbox",Integer.class));
        assertEquals(Boolean.FALSE,jdbc.queryForObject("select production_mode from simulation_decisions where batch_id=?",Boolean.class,b.id()));
    }
    @Test void eachRequiredControlFailsClosedWithoutItsRows() {
        assertFalse(classifier(config(false,false),false).classify(batch(Map.of("conversation","Oi"))).available());
        assertFalse(classifier(config(true,true),false).classify(batch(Map.of("conversation","Oi"))).available());
        assertEquals(0,calls.get());
    }
    @Test void killAndEmergencyBlockAndEmergencySurvivesNewState() {
        var cfg=config(true,false);
        assertFalse(classifier(cfg,true).classify(batch(Map.of("conversation","Oi"))).available());
        jdbc.update("update operational_restrictions set emergency_latched=true");
        assertFalse(classifier(cfg,false).classify(batch(Map.of("conversation","Oi"))).available());
        assertTrue(state(cfg,false).emergency()); assertEquals(0,calls.get());
    }
    @Test void stickerWithoutTextDoesNotCallProvider() {
        assertFalse(classifier(config(true,false),false).classify(batch(Map.of("stickerMessage",Map.of()))).available());
        assertEquals(0,calls.get());
    }
    @Test void matrixRespectsBothIndependentControls() {
        for (boolean bypass : new boolean[]{false,true}) for (boolean consent : new boolean[]{false,true}) {
            jdbc.update("delete from ai_data_consents"); jdbc.update("delete from ai_budget_limits");
            if (!bypass) jdbc.update("insert into ai_budget_limits(singleton_id,daily_limit_micros,monthly_limit_micros) values (1,1000,1000)");
            if (consent) jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values ('test@s.whatsapp.net','CONTACT_REQUEST','openrouter',true,now()+interval '1 day')");
            int before=calls.get();
            assertTrue(classifier(config(bypass,consent),false).classify(batch(Map.of("conversation","Oi"))).available());
            assertEquals(before+1,calls.get());
        }
    }
    @Test void emergencyDuringProviderCallDiscardsResult() {
        var cfg=config(true,false); var ai=new AiSimulationClassifier(jdbc,budgets,clock,cfg,(request,timeout,max)->{
            calls.incrementAndGet(); jdbc.update("update operational_restrictions set emergency_latched=true");
            return validResponse();
        });
        ai.configureOperationalState(state(cfg,false));
        assertFalse(ai.classify(batch(Map.of("conversation","Oi"))).available());
        assertEquals(1,calls.get()); assertTrue(state(cfg,false).emergency());
    }
    @Test void pauseCannotEnableSimulationAgainstEnvironment() {
        var simulation=new WatcherSimulationProperties(); simulation.setEnabled(false);
        var flags=new AutomationProperties(); flags.setKillSwitchEnabled(false);
        var state=new OperationalState(jdbc,flags,config(true,false),simulation,false);
        assertFalse(state.simulation());
        jdbc.update("update operational_restrictions set simulation_paused=true");
        assertFalse(state.simulation());
        jdbc.update("update operational_restrictions set simulation_paused=false");
        assertFalse(state.simulation());
    }

    @Test void legacyDatabaseCannotEnableClassificationWhenEnvironmentDisablesIt() {
        jdbc.update("update automation_settings set ai_classification_enabled=true,kill_switch_enabled=false");
        var cfg=new AiClassificationProperties(true,null,null,"fake","model",10,Duration.ofSeconds(1),null,false,true,false,false);
        assertFalse(classifier(cfg,false).classify(batch(Map.of("conversation","Oi"))).available());
        assertEquals(0,calls.get());
    }

    @Test void legacyValuesCannotOverrideDeclarativeActionFlagsOrKillSwitch() {
        jdbc.update("update automation_settings set ai_classification_enabled=true,kill_switch_enabled=false,intelligent_alerts_enabled=true,auto_reply_enabled=true");
        var flags=new AutomationProperties();
        var state=new OperationalState(jdbc,flags,config(true,false),new WatcherSimulationProperties(),false);
        assertTrue(state.killSwitch()); assertFalse(state.classification());
        assertFalse(state.alerts()); assertFalse(state.replies());
        flags.setKillSwitchEnabled(false);
        assertFalse(state.alerts()); assertFalse(state.replies());
        jdbc.update("update automation_settings set kill_switch_enabled=true,intelligent_alerts_enabled=false,auto_reply_enabled=false");
        flags.setIntelligentAlertsEnabled(true); flags.setAutoReplyEnabled(true);
        assertFalse(state.killSwitch()); assertTrue(state.alerts()); assertTrue(state.replies());
    }

}
