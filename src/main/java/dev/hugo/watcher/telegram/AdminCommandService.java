package dev.hugo.watcher.telegram;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.IdentityService;
import dev.hugo.watcher.notification.ReportService;
import dev.hugo.watcher.policy.AvailabilityState;
import dev.hugo.watcher.telegram.TelegramAdminPersistenceRepository.Status;
import dev.hugo.watcher.telegram.TelegramAdminPersistenceRepository.Budget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminCommandService {
    private static final Logger log = LoggerFactory.getLogger(AdminCommandService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId ADMIN_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);
    private static final Duration REPLY_TTL = Duration.ofMinutes(10);
    private final TelegramAdminPersistenceRepository persistence;
    private final ShadowDecisionQuery decisions;
    private final TransactionTemplate transactions;
    private final TransactionTemplate promptLifecycleSavepoint;
    private final CallbackTokenService tokens;
    private final NotificationOutboxRepository outbox;
    private final Clock clock;
    private final ReportService reports;
    private final IdentityService identities;
    private RuntimeSettingsService runtimeSettings;
    private dev.hugo.watcher.ai.AiClassificationProperties aiProperties;
    private dev.hugo.watcher.ai.ClassificationPromptService promptSettings;
    private dev.hugo.watcher.ai.PromptGenerationService promptGeneration;
    private dev.hugo.watcher.ai.PromptCalibrationQueueRepository promptCalibrationQueue;
    private dev.hugo.watcher.ai.PromptGenerationQueueRepository promptGenerationQueue;
    private dev.hugo.watcher.ai.PromptGenerationMetrics promptGenerationMetrics;
    private ContactPolicyOverviewService contactOverview;
    private dev.hugo.watcher.notification.ContactNotificationRuleRepository contactNotificationRules;
    @Autowired(required = false)
    public void configureContactNotificationRules(dev.hugo.watcher.notification.ContactNotificationRuleRepository rules) {
        this.contactNotificationRules = rules;
    }
    private dev.hugo.watcher.OperationalState operational;
    @Autowired public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }

    /** Production uses managed persistence and read-only decision-query collaborators. */
    public AdminCommandService(TelegramAdminPersistenceRepository persistence, ShadowDecisionQuery decisions,
                               PlatformTransactionManager manager, CallbackTokenService tokens,
                               NotificationOutboxRepository outbox, Clock clock, ReportService reports,
                               IdentityService identities) {
        this.persistence = persistence;
        this.decisions = decisions;
        this.transactions = new TransactionTemplate(manager);
        this.promptLifecycleSavepoint = new TransactionTemplate(manager);
        this.promptLifecycleSavepoint.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        this.tokens = tokens;
        this.outbox = outbox;
        this.clock = clock;
        this.reports = reports;
        this.identities = identities;
    }

    /** Existing constructor preserved for standalone tests during #24. */
    public AdminCommandService(JdbcTemplate jdbc, PlatformTransactionManager manager, CallbackTokenService tokens,
                               NotificationOutboxRepository outbox, Clock clock, ReportService reports) {
        this(new TelegramAdminPersistenceRepository(jdbc), new ShadowDecisionQuery(jdbc, clock),
                manager, tokens, outbox, clock, reports, new IdentityService(jdbc, manager));
    }

    public AdminCommandService(JdbcTemplate jdbc, PlatformTransactionManager manager, CallbackTokenService tokens,
                               NotificationOutboxRepository outbox, Clock clock) {
        this(jdbc, manager, tokens, outbox, clock, null);
    }

    public AdminResult execute(AdminCommand command) {
        if (command == null) return AdminResult.rejected("Comando inválido.");
        // Navigation tokens do not authorize mutations; a second, explicit token confirms them.
        AdminCommand effective = command.name().startsWith("nav_")
                ? new AdminCommand(command.name().substring(4), command.arguments(),
                        command.userId(), command.chatId(), false, command.sourceMessageId())
                : command;
        try {
            if (effective.name().equals("prompt_preview")) return previewPrompt(effective);
            if (List.of("prompt_generate", "prompt_refine").contains(effective.name())) return transactions.execute(status -> generatePrompt(effective));
            if (!effective.confirmed() && (effective.name().equals("prompt_reset")
                    || (effective.name().equals("prompt_restore") && effective.arguments().size() == 1)))
                return requestPromptTransition(effective);
            if (!effective.confirmed() && effective.name().equals("prompt_publish")) validatePublishRequest(effective);
            if (!effective.confirmed() && List.of("prompt_restore_apply", "prompt_reset_apply").contains(effective.name()))
                throw new IllegalArgumentException("invalid_confirmation");
            if (!effective.confirmed() && List.of("prompt_activate", "prompt_restore", "prompt_test").contains(effective.name()))
                validateLegacyPromptArguments(effective);
            if (isMutation(effective.name(), effective.arguments()) && !effective.confirmed() && !effective.name().equals("emergencia"))
                return requestConfirmation(effective);
            return transactions.execute(status -> {
                try {
                    rejectStalePromptTransition(effective);
                    String message = isPromptLifecycleMutation(effective.name())
                            ? promptLifecycleSavepoint.execute(nested -> apply(effective)) : apply(effective);
                    enqueueReply(message, effective);
                    return AdminResult.accepted(message);
                } catch (RuntimeException ex) {
                    if (!(ex instanceof IllegalArgumentException) && !(ex instanceof java.time.format.DateTimeParseException)
                            && !(effective.name().startsWith("prompt") && ex instanceof IllegalStateException)) throw ex;
                    String message = safeError(effective.name(), ex);
                    if (isMutation(effective.name(), effective.arguments())) audit(effective, "REJECTED");
                    enqueueReply(message, effective);
                    return AdminResult.rejected(message);
                }
            });
        } catch (RuntimeException ex) {
            boolean prompt = effective.name().startsWith("prompt");
            if (!(ex instanceof IllegalArgumentException) && !(ex instanceof java.time.format.DateTimeParseException)
                    && !(prompt && ex instanceof IllegalStateException)) throw ex;
            String message = prompt ? "Não foi possível concluir a operação de prompt. Verifique a sessão e tente novamente."
                    : safeError(effective.name(), ex);
            return transactions.execute(status -> {
                if (isMutation(effective.name(), effective.arguments())) audit(effective, "REJECTED");
                enqueueReply(message, effective);
                return AdminResult.rejected(message);
            });
        }
    }

    private AdminResult requestConfirmation(AdminCommand command) {
        return transactions.execute(status -> {
            String token = tokens.issue(command.userId(), command.chatId(),
                    new CallbackAction(command.name(), command.arguments()), clock.instant().plus(CONFIRMATION_TTL));
            boolean inline = inlineCallbacksEnabled();
            String message = inline
                    ? "Confirme a operação /" + command.name() + " em até 5 minutos."
                        + "\nFallback: /confirm " + token
                    : "Confirme em até 5 minutos com /confirm " + token;
            if (inline) {
                String cancel = tokens.issue(command.userId(), command.chatId(),
                        new CallbackAction("cancel", List.of(token)), clock.instant().plus(CONFIRMATION_TTL));
                enqueueReply(message, command, List.of(List.of(
                        Map.of("text", "✅ Confirmar", "data", token),
                        Map.of("text", "❌ Cancelar", "data", cancel))));
            } else {
                enqueueReply(message, command, List.of());
            }
            return AdminResult.confirmation(message);
        });
    }

    private String apply(AdminCommand command) {
        String name = command.name();
        List<String> args = command.arguments();
        String message = switch (name) {
            case "menu", "start" -> "⚙️ Administração Hugo do Zap\nEscolha uma opção:";
            case "settings" -> settingsMenu();
            case "settings_general" -> "Geral e Shadow\n" + status()
                    + "\nO ambiente define o teto de Shadow; a pausa salva no PostgreSQL pode bloquear, nunca liberar, o modo.";
            case "settings_ai" -> aiSettings();
            case "settings_notifications" -> notificationSettings();
            case "regras_notificacao" -> notificationRules();
            case "regra_notificacao" -> configureNotificationRule(command);
            case "settings_profiles" -> profiles();
            case "contato" -> contactOverview(args);
            case "settings_availability" -> availabilitySettings();
            case "settings_templates" -> templates();
            case "settings_budget" -> budget(List.of());
            case "settings_security" -> securitySettings();
            case "settings_audit" -> persistence.recentAudit();
            case "settings_prompt" -> promptSettings();
            case "prompt" -> promptSettings();
            case "prompt_current" -> promptCurrent();
            case "prompt_list" -> promptList();
            case "prompt_new" -> beginPromptInput(command);
            case "prompt_activate", "prompt_restore" -> activatePrompt(command);
            case "prompt_publish" -> publishPrompt(command);
            case "prompt_restore_apply" -> restorePrompt(command);
            case "prompt_reset_apply" -> resetPrompt(command);
            case "prompt_cancel" -> cancelPrompt(command);
            case "prompt_clear" -> clearPrompt(command);
            case "prompt_history" -> promptHistory(args);
            case "prompt_ai" -> beginAiPromptInput(command);
            case "prompt_ai_default" -> beginAiPromptInput(command, true);
            case "prompt_compare" -> comparePrompt(args);
            case "prompt_page" -> promptPage(args);
            case "prompt_test" -> queuePromptTest(command);
            case "availability" -> "Disponibilidade: escolha uma duração ou use /ocupado e /disponivel.";
            case "shadow" -> "Shadow Mode: escolha ligar/desligar a simulação. Ações reais seguem bloqueadas.";
            case "emergency_menu" -> "Emergência: ativação imediata, recuperação mediante confirmação.";
            case "cancel" -> cancel(command);
            case "status" -> status();
            case "ocupado" -> setAvailability(AvailabilityState.UNAVAILABLE, minutes(args));
            case "disponivel" -> setAvailability(AvailabilityState.AVAILABLE, minutes(args));
            case "ferias" -> setHolidayOverride(args);
            case "simulacao" -> setSimulation(args);
            case "relatorio" -> report();
            case "ultimas" -> decisions.recent(args);
            case "decisao" -> decisions.detail(args);
            case "triagem" -> setTriageLegacyFilter(args);
            case "emergencia" -> emergency();
            case "recuperar" -> recoverEmergency(args);
            case "perfis" -> profiles();
            case "perfil" -> setProfile(args);
            case "templates" -> templates();
            case "template" -> setTemplate(args);
            case "feriados" -> holidays();
            case "feriado" -> addHoliday(args);
            case "apelido" -> setContactAlias(args);
            case "orcamento" -> budget(args);
            case "contexto" -> setContextWindow(command);
            case "contexto_default" -> restoreContextWindow(command);
            default -> help();
        };
        if (isMutation(name, args)) audit(command, "APPLIED");
        return message;
    }

    private String cancel(AdminCommand command) {
        if (command.arguments().size() != 1) return "Cancelamento inválido.";
        return tokens.invalidate(command.arguments().getFirst(), command.userId(), command.chatId())
                ? "Operação cancelada." : "Operação já concluída, expirada ou cancelada.";
    }

    private String setContactAlias(List<String> args) {
        if (args.size() < 2) throw new IllegalArgumentException("Use /apelido <jid> <nome...> ou /apelido <jid> remover.");
        String jid = args.getFirst();
        if (args.size() == 2 && "remover".equalsIgnoreCase(args.get(1))) {
            identities.clearManualAlias(jid);
            return "Apelido local removido; o nome disponível do contato será usado.";
        }
        identities.setManualAlias(jid, String.join(" ", args.subList(1, args.size())));
        return "Apelido local do contato atualizado.";
    }

    private String status() {
        if (operational == null) return "Configuração operacional indisponível.";
        List<Status> rows = persistence.availability();
        Status state = rows.isEmpty() ? new Status(null, null) : rows.getFirst();
        String override = state.overrideState() == null || state.overrideExpiresAt() == null
                || !state.overrideExpiresAt().isAfter(clock.instant()) ? "sem override ativo"
                : state.overrideState().toLowerCase() + " até " + state.overrideExpiresAt();
        return "Estado: IA " + on(operational.classification() && operational.classificationConfigured())
                + (operational.classification() && !operational.classificationConfigured() ? " (configuration_invalid)" : "")
                + "; kill switch " + on(operational.killSwitch())
                + "; emergência " + on(operational.emergency()) + "; alertas reais " + on(operational.realModeEnabled() && operational.alerts())
                + "; respostas reais " + on(operational.realModeEnabled() && operational.replies()) + "; simulação " + on(operational.simulation())
                + "; pausa de simulação " + on(operational.simulationPaused())
                + (runtimeSettings == null ? "" : "; contexto " + runtimeSettings.contextWindowHours() + " h") + "; " + override + ".";
    }

    private String settingsMenu() {
        return "⚙️ Configurações\nEscolha uma seção para consultar valores efetivos, origem, dependências e controles disponíveis.";
    }

    private String aiSettings() {
        StringBuilder message = new StringBuilder("IA e modelos\nOpenRouter: ")
                .append(aiProperties != null && aiProperties.openRouterApiKey() != null
                        && !aiProperties.openRouterApiKey().isBlank() ? "configurado" : "não configurado");
        if (aiProperties != null && aiProperties.openRouterModel() != null)
            message.append("\nModelo OpenRouter: ").append(aiProperties.openRouterModel());
        if (aiProperties != null)
            message.append("\nClassificação configurada: ").append(aiProperties.configured() ? "sim" : "não");
        if (runtimeSettings == null) return message.append("\nJanela de contexto: indisponível.").toString();
        RuntimeSettingView setting = runtimeSettings.list().getFirst();
        return message.append("\n\n").append(setting.key()).append(": ").append(setting.effectiveValue())
                .append(" h\nOrigem: ").append(setting.source())
                .append("\nConfigurado: ").append(setting.configuredValue()).append(" h")
                .append("\nEditável: sim · versão ").append(setting.version())
                .append("\nDependências: WATCHER_AI_CONTEXT_WINDOW_HOURS, consentimento do chat e retenção máxima de 7 dias")
                .append("\nAlterar: /contexto <horas> <versão>; restaurar: /contexto_default <versão>. Ambas as operações exigem confirmação.")
                .toString();
    }

    private String notificationSettings() {
        if (operational == null) return "Notificações e triagem: estado operacional indisponível.";
        return "Notificações e triagem\nAlertas simulados configurados: " + on(operational.simulatedAlerts())
                + "\nRespostas simuladas configuradas: " + on(operational.simulatedReplies())
                + "\nAlertas reais efetivos: " + on(operational.alerts())
                + "\nRespostas reais efetivas: " + on(operational.replies())
                + "\nBloqueios da decisão: consulte /decisao <id>; consentimento, política e horário continuam independentes."
                + "\nRegras determinísticas opt-in: /regras_notificacao.";
    }

    private String notificationRules() {
        if (contactNotificationRules == null) return "Regras de notificação indisponíveis.";
        var rows = contactNotificationRules.all();
        if (rows.isEmpty()) return "Nenhuma regra por contato/marcador configurada. Sem alterações no comportamento legado."
                + "\nCriar: /regra_notificacao marcador JC WORK_ONLY NORMAL"
                + "\nPrioridade: /regra_notificacao marcador CH HERDAR PRIORITY"
                + "\nContato: /regra_notificacao contato <jid> HERDAR PRIORITY"
                + "\nRemover: /regra_notificacao remover marcador|contato <valor>.";
        StringBuilder text = new StringBuilder("Regras de notificação (opt-in):");
        for (var rule : rows) {
            text.append("\n").append(rule.selectorType()).append(" ").append(rule.selector())
                    .append(" · perfil ").append(rule.profile() == null ? "HERDAR" : rule.profile())
                    .append(" · prioridade ").append(rule.priority());
            if (text.length() > 2800) { text.append("\n… mais regras não exibidas"); break; }
        }
        return text.append("\nO envio segue sujeito a arquivamento, leitura, horário, feriados e demais gates.").toString();
    }

    private String configureNotificationRule(AdminCommand command) {
        if (contactNotificationRules == null) throw new IllegalStateException("notification_rules_unavailable");
        List<String> args = command.arguments();
        if (args.isEmpty() || "listar".equalsIgnoreCase(args.getFirst())) return notificationRules();
        boolean remove = "remover".equalsIgnoreCase(args.getFirst());
        int start = remove ? 1 : 0;
        if (args.size() != (remove ? 3 : 4))
            throw new IllegalArgumentException("Use /regra_notificacao marcador|contato <valor> WORK_ONLY|ANYTIME|HERDAR NORMAL|PRIORITY; "
                    + "ou /regra_notificacao remover marcador|contato <valor>.");
        String type = switch (args.get(start).toLowerCase(java.util.Locale.ROOT)) {
            case "marcador" -> "MARKER";
            case "contato" -> "CONTACT";
            default -> throw new IllegalArgumentException("Use marcador ou contato.");
        };
        String selector = "MARKER".equals(type)
                ? dev.hugo.watcher.notification.ContactNotificationRules.marker(args.get(start + 1))
                : identities.canonicalJidFor(args.get(start + 1));
        if (selector == null || selector.endsWith("@g.us") || selector.endsWith("@broadcast"))
            throw new IllegalArgumentException("Regra por contato exige um identificador privado canônico.");
        if (remove) {
            return contactNotificationRules.remove(type, selector, command.userId(), clock.instant())
                    ? "Regra removida. Histórico de auditoria preservado." : "Regra já não existia.";
        }
        String requested = args.get(2).toUpperCase(java.util.Locale.ROOT);
        String profile = switch (requested) {
            case "HERDAR" -> null;
            case "WORK_ONLY", "ANYTIME" -> requested;
            default -> throw new IllegalArgumentException("Perfil deve ser WORK_ONLY, ANYTIME ou HERDAR.");
        };
        String priority = args.get(3).toUpperCase(java.util.Locale.ROOT);
        return contactNotificationRules.save(type, selector, profile, priority,
                command.userId(), clock.instant())
                ? "Regra salva. PRIORITY antecipa a conversa na fila de notificações elegíveis; não altera urgência nem autoriza resposta."
                : "Regra idêntica à já configurada; nenhuma alteração aplicada.";
    }

    private String availabilitySettings() {
        List<Status> rows = persistence.availability();
        if (rows.isEmpty() || rows.getFirst().overrideState() == null)
            return "Disponibilidade: sem override temporário. Use /ocupado ou /disponivel; /ferias registra um período.";
        Status row = rows.getFirst();
        if (row.overrideExpiresAt() == null || !row.overrideExpiresAt().isAfter(clock.instant()))
            return "Disponibilidade: override expirado. As regras de política voltaram a valer.";
        return "Disponibilidade: " + row.overrideState().toLowerCase() + " até " + row.overrideExpiresAt()
                + ". Use /ocupado ou /disponivel para alterar.";
    }

    private String securitySettings() {
        if (operational == null) return "Segurança: configuração operacional indisponível.";
        return "Segurança\nKill switch: " + on(operational.killSwitch())
                + "\nEmergência: " + on(operational.emergency())
                + "\nEnvio real WhatsApp: " + on(operational.realModeEnabled() && !operational.killSwitch())
                + "\nChaves: " + (aiProperties != null && aiProperties.openRouterApiKey() != null
                        && !aiProperties.openRouterApiKey().isBlank() ? "OpenRouter configurada" : "OpenRouter não configurada")
                + "\nGates reais e segredos só podem ser alterados no ambiente de implantação.";
    }

    private String promptSettings() {
        if (promptSettings == null) return "Prompts: gerenciamento de versões indisponível.";
        var active = promptSettings.current(true);
        return "Prompts\nVersão Shadow ativa: v" + active.revision() + " · SHA-256 " + active.sha256().substring(0,12)
                + "\nRevisões são imutáveis; ativação afeta somente o classificador Shadow."
                + "\nUse /prompt_new para editar, /prompt_preview para revisar, /prompt_history para histórico e /prompt_reset para o Java default."
                + "\nUse /prompt_current, /prompt_list ou /prompt_compare <v1> <v2> para consultar versões.";
    }

    private String promptCurrent() {
        if (promptSettings == null) return "Prompts: gerenciamento de versões indisponível.";
        var active = promptSettings.current(true);
        String body = active.revision() == 0 ? dev.hugo.watcher.ai.ClassificationPromptService.DEFAULT_OPERATIONAL_PROMPT
                : promptSettings.revision(active.revision()).body();
        int end = Math.min(1800, body.length());
        return "Prompt operacional ativo v" + active.revision() + " · SHA-256 " + active.sha256().substring(0,12)
                + "\n\n" + body.substring(0,end)
                + (end < body.length() ? "\n… use /prompt_page " + active.revision() + " 1800" : "");
    }

    private String promptList() {
        if (promptSettings == null) return "Prompts: gerenciamento de versões indisponível.";
        int active = promptSettings.current(true).revision();
        List<String> revisions = promptSettings.latest().stream().map(value -> "v" + value.revision()
                + (active == value.revision() ? " · ativa em Shadow" : " · rascunho")
                + " · SHA-256 " + value.sha256().substring(0,12)).toList();
        return "Revisões do prompt:\n" + (revisions.isEmpty() ? "nenhuma; o default está ativo" : String.join("\n", revisions))
                + "\nComparar: /prompt_compare <v1> <v2>.";
    }

    private String beginPromptInput(AdminCommand command) {
        if (promptSettings == null) return "Prompts: gerenciamento de versões indisponível.";
        promptSettings.beginManualInput(command.userId(), command.chatId(), clock.instant());
        return "Rascunho criado a partir do prompt ativo. Envie partes de até 4096 caracteres nos próximos 10 minutos "
                + "(limite total: 16000). Use /prompt_clear para reescrever, /prompt_preview para revisar ou /prompt_cancel para descartar.";
    }

    private String beginAiPromptInput(AdminCommand command) {
        return beginAiPromptInput(command, false);
    }

    private String beginAiPromptInput(AdminCommand command, boolean useJavaDefault) {
        if (promptSettings == null) return "Prompts: gerenciamento de versões indisponível.";
        Instant now = clock.instant();
        if (useJavaDefault) promptSettings.beginDefaultInput(command.userId(), command.chatId(), now);
        else promptSettings.beginManualInput(command.userId(), command.chatId(), now);
        return "Rascunho iniciado com o " + (useJavaDefault ? "Java default" : "prompt ativo")
                + ". Use /prompt_generate <objetivo, tom e regras explícitas> para criar a proposta; /prompt_refine <instruções> refina até três vezes. Não inclua mensagens de conversas. A proposta continua em rascunho.";
    }

    private AdminResult generatePrompt(AdminCommand command) {
        if (promptGenerationQueue == null || promptSettings == null)
            return AdminResult.rejected("Geração de prompt indisponível no momento.");
        if (command.arguments().isEmpty()) return AdminResult.rejected("Informe instruções explícitas de objetivo, tom ou regras.");
        try {
            var draft = pendingPrompt(command, clock.instant());
            String instructions = String.join(" ", command.arguments());
            if (instructions.length() > 4096) throw new IllegalArgumentException("instructions_too_long");
            String operation = command.name().equals("prompt_refine") ? "REFINE" : "GENERATE";
            var jobId = promptGenerationQueue.enqueue(draft.id(), draft.version(), command.userId(), command.chatId(),
                    operation, instructions, clock.instant());
            recordPromptGenerationEnqueued(jobId, operation);
            String message = "Solicitação de proposta recebida (" + jobId.toString().substring(0, 8)
                    + "). O resultado será enviado nesta conversa privada quando estiver pronto.";
            enqueueReply(message, command);
            return AdminResult.accepted(message);
        } catch (RuntimeException exception) {
            String message = "Não foi possível gerar a proposta; o rascunho atual foi preservado.";
            return transactions.execute(status -> {
                enqueueReply(message, command);
                return AdminResult.rejected(message);
            });
        }
    }

    private void recordPromptGenerationEnqueued(UUID jobId, String operation) {
        Runnable record = () -> {
            if (promptGenerationMetrics != null) promptGenerationMetrics.enqueued(operation);
            log.info("prompt_generation event=ENQUEUED jobId={} operation={}", jobId, operation);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { record.run(); }
            });
        } else {
            record.run();
        }
    }

    private dev.hugo.watcher.ai.ClassificationPromptRepository.Draft pendingPrompt(AdminCommand command, Instant now) {
        if (promptSettings == null) throw new IllegalStateException("prompt_unavailable");
        return promptSettings.pendingManualInput(command.userId(), command.chatId(), now)
                .orElseThrow(() -> new IllegalStateException("prompt_session_unavailable"));
    }

    private AdminResult previewPrompt(AdminCommand command) {
        if (!command.arguments().isEmpty()) throw new IllegalArgumentException("invalid_preview");
        return transactions.execute(status -> {
            Instant now = clock.instant();
            var draft = pendingPrompt(command, now);
            var preview = promptSettings.preview(draft.id(), command.userId(), now);
            String message = "Prévia do rascunho\n" + preview.diffSummary() + "\nValidação: "
                    + (preview.validation().valid() ? "válida" : "inválida (" + String.join(", ", preview.validation().violationCodes()) + ")")
                    + "\n\n" + preview.body();
            List<List<Map<String, String>>> keyboard = List.of();
            var active = promptSettings.activeState();
            boolean sameBase = java.util.Objects.equals(active.revisionId(), preview.baseRevision());
            if (!sameBase) message += "\n\nO prompt ativo mudou; inicie um novo rascunho antes de ativar.";
            if (preview.validation().valid() && sameBase) {
                Instant expiry = now.plus(CONFIRMATION_TTL).isBefore(draft.expiresAt()) ? now.plus(CONFIRMATION_TTL) : draft.expiresAt();
                String token = tokens.issue(command.userId(), command.chatId(), new CallbackAction("nav_prompt_publish",
                        List.of(draft.id().toString(), Long.toString(preview.draftVersion()),
                                Long.toString(preview.baseRevision() == null ? 0 : preview.baseRevision()),
                                Long.toString(active.version()))), expiry);
                message += "\n\nPara solicitar ativação: /confirm " + token;
                if (inlineCallbacksEnabled()) keyboard = List.of(List.of(Map.of("text", "Ativar após confirmação", "data", token)));
            }
            enqueueReply(message, command, keyboard);
            return AdminResult.accepted(message);
        });
    }

    private String publishPrompt(AdminCommand command) {
        if (command.arguments().size() != 4) throw new IllegalArgumentException("invalid_prompt_confirmation");
        Instant now = clock.instant();
        UUID id = UUID.fromString(command.arguments().get(0));
        var draft = pendingPrompt(command, now);
        if (!draft.id().equals(id) || !draft.status().equals("preview")) throw new IllegalStateException("stale_preview");
        var saved = promptSettings.activate(id, command.userId(), Long.parseLong(command.arguments().get(1)),
                Long.parseLong(command.arguments().get(2)), Long.parseLong(command.arguments().get(3)), now);
        if (!promptSettings.endManualInput(command.userId(), command.chatId(), id, now)) throw new IllegalStateException("stale_session");
        return "Prompt v" + saved.revision() + " ativado em Shadow.";
    }

    private void validatePublishRequest(AdminCommand command) {
        if (command.arguments().size() != 4) throw new IllegalArgumentException("invalid_confirmation");
        UUID id = UUID.fromString(command.arguments().get(0));
        long version = Long.parseLong(command.arguments().get(1));
        long base = Long.parseLong(command.arguments().get(2));
        long stateVersion = Long.parseLong(command.arguments().get(3));
        var draft = pendingPrompt(command, clock.instant());
        var active = promptSettings.activeState();
        if (!draft.id().equals(id) || !draft.status().equals("preview") || draft.version() != version
                || (draft.baseRevision() == null ? 0 : draft.baseRevision()) != base
                || (active.revisionId() == null ? 0 : active.revisionId()) != base || active.version() != stateVersion
                || !promptSettings.validateDraft(draft.body()).valid()) throw new IllegalStateException("stale_preview");
    }

    private static void validateLegacyPromptArguments(AdminCommand command) {
        int size = command.name().equals("prompt_test") ? 1 : 2;
        if (command.arguments().size() != size) throw new IllegalArgumentException("invalid_prompt_arguments");
        for (String argument : command.arguments()) Long.parseLong(argument);
    }

    private String cancelPrompt(AdminCommand command) {
        Instant now = clock.instant();
        var draft = pendingPrompt(command, now);
        if (!promptSettings.cancel(draft.id(), command.userId(), now)
                || !promptSettings.endManualInput(command.userId(), command.chatId(), draft.id(), now))
            throw new IllegalStateException("stale_session");
        audit(command, "APPLIED");
        return "Rascunho descartado.";
    }

    private String clearPrompt(AdminCommand command) {
        Instant now = clock.instant();
        var draft = pendingPrompt(command, now);
        promptSettings.clearManualDraft(draft.id(), command.userId(), now);
        audit(command, "APPLIED");
        return "Rascunho limpo. Envie o novo texto em partes e use /prompt_preview para revisar.";
    }

    private AdminResult requestPromptTransition(AdminCommand command) {
        return transactions.execute(status -> {
            if (promptSettings == null) throw new IllegalStateException("prompt_unavailable");
            boolean reset = command.name().equals("prompt_reset");
            if (reset && !command.arguments().isEmpty()) throw new IllegalArgumentException("invalid_reset");
            var state = promptSettings.activeState();
            var current = promptSettings.forRevision(state.revisionId() == null ? 0 : Math.toIntExact(state.revisionId()));
            int targetId = reset ? 0 : Integer.parseInt(command.arguments().getFirst());
            if (!reset && targetId <= 0) throw new IllegalArgumentException("invalid_revision");
            var target = promptSettings.forRevision(targetId);
            List<String> args = reset ? List.of(Integer.toString(current.revision()), Long.toString(state.version()))
                    : List.of(Integer.toString(targetId), Integer.toString(current.revision()), Long.toString(state.version()));
            String token = tokens.issue(command.userId(), command.chatId(), new CallbackAction(
                    reset ? "prompt_reset_apply" : "prompt_restore_apply", args), clock.instant().plus(CONFIRMATION_TTL));
            String message = "Prompt atual v" + current.revision() + ":\n" + current.content()
                    + "\n\nDestino " + (reset ? "Java default" : "v" + targetId) + ":\n" + target.content()
                    + "\n\n" + (reset ? "O Java default será usado; as revisões personalizadas permanecem no histórico.\n" : "")
                    + "Confirme em até 5 minutos: /confirm " + token;
            List<List<Map<String, String>>> keyboard = inlineCallbacksEnabled()
                    ? List.of(List.of(Map.of("text", "Confirmar", "data", token))) : List.of();
            enqueueReply(message, command, keyboard);
            return AdminResult.confirmation(message);
        });
    }

    private String restorePrompt(AdminCommand command) {
        if (command.arguments().size() != 3) throw new IllegalArgumentException("invalid_restore");
        var restored = promptSettings.restore(Long.parseLong(command.arguments().get(0)), command.userId(),
                Long.parseLong(command.arguments().get(1)), Long.parseLong(command.arguments().get(2)), clock.instant());
        return "Prompt v" + restored.revision() + " restaurado em Shadow.";
    }

    private String resetPrompt(AdminCommand command) {
        if (command.arguments().size() != 2) throw new IllegalArgumentException("invalid_reset");
        promptSettings.resetToDefault(command.userId(), Long.parseLong(command.arguments().getFirst()),
                Long.parseLong(command.arguments().get(1)), clock.instant());
        return "Prompt Java default restaurado em Shadow; o histórico foi preservado.";
    }

    private String promptHistory(List<String> args) {
        if (promptSettings == null || args.size() > 1) throw new IllegalArgumentException("invalid_history");
        int page = args.isEmpty() ? 0 : Integer.parseInt(args.getFirst());
        if (page < 0 || page > 9) throw new IllegalArgumentException("invalid_history_page");
        var history = promptSettings.history(100);
        int start = Math.min(page * 10, history.size()), end = Math.min(start + 10, history.size());
        StringBuilder message = new StringBuilder("Histórico de prompts · página ").append(page + 1).append('\n');
        for (var entry : history.subList(start, end)) message.append(entry.event()).append(" · v")
                .append(entry.nextRevision() == null ? 0 : entry.nextRevision()).append(" · ")
                .append(entry.contentHash(), 0, 12).append(" · ").append(entry.createdAt()).append('\n');
        if (end < history.size()) message.append("Próxima: /prompt_history ").append(page + 1);
        return message.toString();
    }

    private String activatePrompt(AdminCommand command) {
        if (command.arguments().size() != 2) throw new IllegalArgumentException("Use /prompt_activate <revisão> <versão-ativa>.");
        int revision = Integer.parseInt(command.arguments().get(0));
        long version = Long.parseLong(command.arguments().get(1));
        long updated = promptSettings.activate(revision, version, command.userId(), clock.instant());
        return "Prompt v" + revision + " ativado no Shadow (estado " + updated + "). Gates e ações reais não foram alterados.";
    }

    private String comparePrompt(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /prompt_compare <v1> <v2>.");
        return promptSettings.compare(Integer.parseInt(args.get(0)), Integer.parseInt(args.get(1)), 2600);
    }

    private String promptPage(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /prompt_page <revisão> <posição>.");
        int revision = Integer.parseInt(args.get(0)), offset = Integer.parseInt(args.get(1));
        String body = revision == 0 ? dev.hugo.watcher.ai.ClassificationPromptService.DEFAULT_OPERATIONAL_PROMPT
                : promptSettings.revision(revision).body();
        if (offset < 0 || offset > body.length()) throw new IllegalArgumentException("Posição fora do prompt.");
        int end = Math.min(offset + 1800, body.length());
        return "Prompt v" + revision + " · caracteres " + offset + "–" + end + "\n\n" + body.substring(offset,end)
                + (end < body.length() ? "\n\nPróxima página: /prompt_page " + revision + " " + end : "");
    }

    private String queuePromptTest(AdminCommand command) {
        if (command.arguments().size() != 1) throw new IllegalArgumentException("Use /prompt_test <revisão>.");
        if (promptCalibrationQueue == null) throw new IllegalStateException("prompt calibration queue unavailable");
        int revision = Integer.parseInt(command.arguments().getFirst());
        if (revision < 0 || revision != 0 && promptSettings == null)
            throw new IllegalArgumentException("Revisão de prompt inválida.");
        if (revision != 0) promptSettings.revision(revision);
        var id = promptCalibrationQueue.enqueue(revision, command.userId(), command.chatId(), clock.instant());
        return "Teste sintético do prompt v" + revision + " enfileirado (" + id.toString().substring(0,8)
                + "). Será executado uma vez, somente em Shadow, com orçamento e sem usar conversa real.";
    }

    /** Accept prompt text only from a live, one-use session bound to this authorized Telegram identity. */
    public AdminResult acceptPromptInput(String body, long userId, long chatId, Integer messageId) {
        if (promptSettings == null || !promptSettings.hasPendingInput(userId, chatId, clock.instant())) return null;
        AdminCommand command = new AdminCommand("prompt_draft", List.of(), userId, chatId, true);
        try {
            return transactions.execute(status -> {
                Instant now = clock.instant();
                var pending = promptSettings.pendingManualInput(userId, chatId, now);
                if (pending.isPresent()) {
                    promptSettings.appendManualPart(pending.get().id(), userId, body, now);
                    String message = "Parte adicionada. Envie outra parte, use /prompt_preview ou /prompt_cancel.";
                    enqueueReply(message, command);
                    return AdminResult.accepted(message);
                }
                var draft = promptSettings.acceptInput(userId, chatId, body, clock.instant());
                String message = "Rascunho v" + draft.revision() + " salvo · SHA-256 " + draft.sha256().substring(0,12)
                        + ". Compare com /prompt_compare, depois use /prompt_activate " + draft.revision() + " "
                        + promptSettings.activeVersion() + " para ativar após confirmação.";
                persistence.audit("prompt_draft", "APPLIED", userId, chatId, clock.instant());
                enqueueReply(message, command);
                return AdminResult.accepted(message);
            });
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return transactions.execute(status -> {
                String message = "Não foi possível adicionar a parte. Verifique a sessão e o limite de caracteres.";
                enqueueReply(message, command, keyboardFor(new AdminCommand("settings_prompt", List.of(), userId, chatId, false)));
                return AdminResult.rejected(message);
            });
        }
    }

    private String setContextWindow(AdminCommand command) {
        if (command.arguments().size() != 2) throw new IllegalArgumentException("Use /contexto <horas> <versão>.");
        RuntimeSettingView setting = requireRuntimeSettings().set("ai.context_window_hours", command.arguments().get(0),
                Long.parseLong(command.arguments().get(1)), command.userId(), clock.instant());
        return "Janela de contexto atualizada para " + setting.effectiveValue() + " horas (versão " + setting.version() + ").";
    }

    private String restoreContextWindow(AdminCommand command) {
        if (command.arguments().size() != 1) throw new IllegalArgumentException("Use /contexto_default <versão>.");
        RuntimeSettingView setting = requireRuntimeSettings().restore("ai.context_window_hours",
                Long.parseLong(command.arguments().getFirst()), command.userId(), clock.instant());
        return "Janela de contexto restaurada ao valor " + setting.effectiveValue() + " horas (" + setting.source() + ").";
    }

    private RuntimeSettingsService requireRuntimeSettings() {
        if (runtimeSettings == null) throw new IllegalStateException("runtime settings unavailable");
        return runtimeSettings;
    }

    private String setAvailability(AvailabilityState state, int minutes) {
        Instant now = clock.instant();
        persistence.setAvailability(state.name(), now, now.plusSeconds(minutes * 60L));
        return "Disponibilidade " + state.name().toLowerCase() + " por " + minutes + " minutos.";
    }

    private String setHolidayOverride(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /ferias AAAA-MM-DD AAAA-MM-DD.");
        LocalDate first = LocalDate.parse(args.get(0));
        LocalDate last = LocalDate.parse(args.get(1));
        if (first.isBefore(LocalDate.now(clock.withZone(ADMIN_ZONE))) || last.isBefore(first)
                || last.isAfter(first.plusDays(89))) throw new IllegalArgumentException("Período de férias deve começar hoje ou depois e durar no máximo 90 dias.");
        Instant starts = first.atStartOfDay(ADMIN_ZONE).toInstant();
        Instant expires = last.plusDays(1).atStartOfDay(ADMIN_ZONE).toInstant();
        persistence.setHolidayOverride(starts, expires, clock.instant());
        return "Férias registradas de " + first + " a " + last + ".";
    }

    private String setSimulation(List<String> args) {
        if (args.size() != 1) throw new IllegalArgumentException("Use /simulacao on ou /simulacao off.");
        boolean enabled = switch (args.getFirst().toLowerCase()) {
            case "on", "ligar" -> true;
            case "off", "desligar" -> false;
            default -> throw new IllegalArgumentException("Use /simulacao on ou /simulacao off.");
        };
        persistence.setSimulationPaused(!enabled, clock.instant());
        return enabled ? "Pausa de simulação removida; o ambiente continua determinando se ela pode funcionar." : "Simulação pausada.";
    }

    private String report() {
        if (reports != null) {
            reports.scheduleOnDemand(LocalDate.now(clock.withZone(ADMIN_ZONE)));
            return "Resumo diário de simulação enfileirado para entrega no Telegram.";
        }
        Instant cutoff = clock.instant().minus(Duration.ofHours(24));
        Integer decisions = persistence.decisionsSince(cutoff);
        Integer alerts = persistence.alertsSince(cutoff);
        Long reserved = persistence.reservedSince(cutoff);
        return "Últimas 24h: " + decisions + " decisões simuladas, " + alerts + " alertas previstos; orçamento reservado: "
                + (reserved == null ? 0 : reserved) + " micros. Sem conteúdo de conversas.";
    }

    private String emergency() {
        persistence.setEmergency(true, clock.instant());
        return "Emergência ativada: IA, alertas e respostas bloqueados até recuperação explícita.";
    }

    private String recoverEmergency(List<String> args) {
        if (!args.isEmpty()) throw new IllegalArgumentException("Use /recuperar e confirme a operação.");
        persistence.setEmergency(false, clock.instant());
        return "Emergência desativada; os controles do ambiente continuam em vigor.";
    }

    private String profiles() {
        List<String> names = persistence.profileNames();
        return "Perfis: " + String.join(", ", names) + ". Controle: /perfil <chat> <perfil>; alteração exige confirmação.\n"
                + "Consulta somente leitura: /contato <jid>.";
    }

    private String contactOverview(List<String> args) {
        if (args.size() != 1) throw new IllegalArgumentException("Use /contato <jid>.");
        if (contactOverview == null) throw new IllegalStateException("contact overview unavailable");
        return contactOverview.describe(args.getFirst());
    }

    private String setProfile(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /perfil <chat> <perfil>.");
        String chat = args.get(0);
        if (chat.length() > 255 || !chat.matches("[\\p{L}\\p{N}_.:@+-]{3,255}"))
            throw new IllegalArgumentException("Identificador de conversa inválido.");
        Integer knownChat = persistence.knownChat(chat);
        Integer knownProfile = persistence.knownProfile(args.get(1));
        if (knownChat == null || knownChat == 0) throw new IllegalArgumentException("Conversa não encontrada.");
        if (knownProfile == null || knownProfile == 0) throw new IllegalArgumentException("Perfil não encontrado.");
        persistence.setChatProfile(chat, args.get(1), clock.instant());
        return "Perfil da conversa atualizado.";
    }

    private String setTriageLegacyFilter(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /triagem <id da decisão> on|off.");
        boolean enabled = switch (args.get(1).toLowerCase(java.util.Locale.ROOT)) {
            case "on", "ligar" -> true;
            case "off", "desligar" -> false;
            default -> throw new IllegalArgumentException("Use /triagem <id da decisão> on|off.");
        };
        persistence.setTriageLegacyFilter(args.getFirst(), enabled, clock.instant());
        return enabled
                ? "Opt-in do filtro legado salvo para a conversa. O filtro global também precisa estar habilitado; somente IGNORE privado e isolado pode ser suprimido."
                : "Opt-in do filtro legado removido para a conversa.";
    }

    private String templates() {
        List<String> entries = persistence.templateNames();
        return "Templates: " + String.join(", ", entries) + ". Controle: /template <id> on|off; alteração exige confirmação. Respostas reais continuam desligadas pelo kill switch.";
    }

    private String setTemplate(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Use /template <id> on ou /template <id> off.");
        boolean enabled = switch (args.get(1).toLowerCase()) {
            case "on", "ligar" -> true;
            case "off", "desligar" -> false;
            default -> throw new IllegalArgumentException("Use /template <id> on ou /template <id> off.");
        };
        int updated = persistence.setTemplateEnabled(enabled, args.get(0));
        if (updated != 1) throw new IllegalArgumentException("Template não encontrado.");
        return "Template atualizado; envio real segue protegido pelo kill switch.";
    }

    private String holidays() {
        LocalDate today = LocalDate.now(clock.withZone(ADMIN_ZONE));
        List<String> entries = persistence.holidaysBetween(today, today.plusDays(30));
        return "Próximos feriados: " + (entries.isEmpty() ? "nenhum cadastrado" : String.join("; ", entries))
                + ". Controle: /feriado AAAA-MM-DD BR|SP|LINS https://fonte nome; alteração exige confirmação.";
    }

    private String addHoliday(List<String> args) {
        if (args.size() < 4) throw new IllegalArgumentException("Use /feriado AAAA-MM-DD BR|SP|LINS https://fonte nome.");
        LocalDate date = LocalDate.parse(args.get(0));
        String jurisdiction = args.get(1).toUpperCase();
        if (!List.of("BR", "SP", "LINS").contains(jurisdiction)) throw new IllegalArgumentException("Jurisdição inválida.");
        if (!args.get(2).matches("https://[^\\s]{1,500}")) throw new IllegalArgumentException("Fonte deve ser URL HTTPS.");
        String name = String.join(" ", args.subList(3, args.size())).strip();
        if (name.isBlank() || name.length() > 200) throw new IllegalArgumentException("Nome do feriado inválido.");
        persistence.upsertHoliday(date, jurisdiction, name, args.get(2), clock.instant());
        return "Feriado registrado com atestação manual.";
    }

    private String budget(List<String> args) {
        if (args.isEmpty()) {
            List<Budget> values = persistence.budgetLimits();
            if (values.isEmpty()) return "Limites de orçamento ausentes; chamadas pagas bloqueadas. Controle: /orcamento <diário-micros> <mensal-micros>.";
            Budget value = values.getFirst();
            return "Limite diário: " + value.daily() + " micros; mensal: " + value.monthly() + " micros. Controle: /orcamento <diário-micros> <mensal-micros>; alteração exige confirmação.";
        }
        if (args.size() != 2) throw new IllegalArgumentException("Use /orcamento <diário-micros> <mensal-micros>.");
        long daily = Long.parseLong(args.get(0));
        long monthly = Long.parseLong(args.get(1));
        if (daily < 1 || monthly < 1 || daily > 1_000_000_000_000L || monthly > 1_000_000_000_000L)
            throw new IllegalArgumentException("Limites devem estar entre 1 e 1000000000000 micros.");
        persistence.upsertBudget(daily, monthly, clock.instant());
        return "Limites de orçamento atualizados em micros; nenhuma chamada paga foi iniciada.";
    }

    private void audit(AdminCommand command, String outcome) {
        persistence.audit(command.name(), outcome, command.userId(), command.chatId(), clock.instant());
    }

    private void enqueueReply(String message, AdminCommand command) {
        enqueueReply(message, command, keyboardFor(command));
    }

    private void enqueueReply(String message, AdminCommand command, List<List<Map<String, String>>> keyboard) {
        final int partLimit = 3000;
        int offset = 0;
        do {
            int end = Math.min(offset + partLimit, message.length());
            if (end < message.length() && end > offset && Character.isHighSurrogate(message.charAt(end - 1))) end--;
            boolean last = end == message.length();
            AdminCommand part = offset == 0 ? command : new AdminCommand(command.name(), command.arguments(),
                    command.userId(), command.chatId(), command.confirmed());
            enqueueReplyPart(message.substring(offset, end), part, last ? keyboard : List.of());
            offset = end;
        } while (offset < message.length());
    }

    private void enqueueReplyPart(String message, AdminCommand command, List<List<Map<String, String>>> keyboard) {
        Instant now = clock.instant();
        String event = command.sourceMessageId() == null ? "telegram_admin_reply" : "telegram_admin_edit";
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("eventType", event);
            payload.put("text", message);
            if (command.sourceMessageId() != null) payload.put("messageId", command.sourceMessageId());
            if (!keyboard.isEmpty()) payload.put("keyboard", keyboard);
            outbox.enqueue(event + ":" + UUID.randomUUID(), event,
                    JSON.writeValueAsString(payload), "[]", now, now.plus(REPLY_TTL));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("could not queue Telegram admin response", ex);
        }
    }

    private boolean inlineCallbacksEnabled() {
        return telegramProperties != null
                && (telegramProperties.isPollingEnabled() || telegramProperties.isWebhookEnabled());
    }

    private List<List<Map<String, String>>> keyboardFor(AdminCommand command) {
        if (!inlineCallbacksEnabled()) return List.of();
        long user = command.userId(), chat = command.chatId();
        Instant expiry = clock.instant().plus(CONFIRMATION_TTL);
        String name = command.name();
        List<List<Map<String, String>>> rows = new java.util.ArrayList<>();
        switch (name) {
            case "menu", "start" -> {
                rows.add(List.of(button("📊 Status", "status", List.of(), user, chat, expiry),
                        button("🕐 Disponibilidade", "availability", List.of(), user, chat, expiry)));
                rows.add(List.of(button("🧪 Shadow Mode", "shadow", List.of(), user, chat, expiry),
                        button("👥 Perfis / conversas", "perfis", List.of(), user, chat, expiry)));
                rows.add(List.of(button("📝 Templates", "templates", List.of(), user, chat, expiry),
                        button("💰 Orçamento", "orcamento", List.of(), user, chat, expiry)));
                rows.add(List.of(button("📈 Relatórios", "relatorio", List.of(), user, chat, expiry),
                        button("🚨 Emergência", "emergency_menu", List.of(), user, chat, expiry)));
                rows.add(List.of(button("⚙️ Configurações", "settings", List.of(), user, chat, expiry)));
                if (telegramProperties != null && telegramProperties.getMiniAppUrl() != null
                        && !telegramProperties.getMiniAppUrl().isBlank())
                    rows.add(List.of(Map.of("text", "🌐 Abrir Mini App",
                            "webAppUrl", telegramProperties.getMiniAppUrl())));
            }
            case "availability" -> rows.add(List.of(
                    button("Ocupado 60 min", "ocupado", List.of("60"), user, chat, expiry),
                    button("Disponível 60 min", "disponivel", List.of("60"), user, chat, expiry)));
            case "shadow" -> rows.add(List.of(
                    button("Simulação ON", "simulacao", List.of("on"), user, chat, expiry),
                    button("Simulação OFF", "simulacao", List.of("off"), user, chat, expiry)));
            case "emergency_menu" -> rows.add(List.of(
                    button("🚨 Ativar emergência", "emergencia", List.of(), user, chat, expiry),
                    button("Recuperar", "recuperar", List.of(), user, chat, expiry)));
            case "settings" -> {
                rows.add(List.of(button("Geral e Shadow", "settings_general", List.of(), user, chat, expiry),
                        button("IA e modelos", "settings_ai", List.of(), user, chat, expiry)));
                rows.add(List.of(button("Prompts", "settings_prompt", List.of(), user, chat, expiry),
                        button("Notificações / triagem", "settings_notifications", List.of(), user, chat, expiry)));
                rows.add(List.of(button("Perfis / contatos", "settings_profiles", List.of(), user, chat, expiry),
                        button("Disponibilidade", "settings_availability", List.of(), user, chat, expiry)));
                rows.add(List.of(button("Templates", "settings_templates", List.of(), user, chat, expiry),
                        button("Orçamento / limites", "settings_budget", List.of(), user, chat, expiry)));
                rows.add(List.of(button("Segurança", "settings_security", List.of(), user, chat, expiry),
                        button("Status / auditoria", "settings_audit", List.of(), user, chat, expiry)));
            }
            case "settings_prompt" -> {
                rows.add(List.of(button("Prompt atual", "prompt_current", List.of(), user, chat, expiry),
                        button("Histórico", "prompt_history", List.of(), user, chat, expiry)));
                rows.add(List.of(button("Editar manualmente", "prompt_new", List.of(), user, chat, expiry),
                        button("Criar com IA · ativo", "prompt_ai", List.of(), user, chat, expiry)));
                rows.add(List.of(button("Criar com IA · Java default", "prompt_ai_default", List.of(), user, chat, expiry)));
                rows.add(List.of(button("Prévia", "prompt_preview", List.of(), user, chat, expiry),
                        button("Restaurar Java default", "prompt_reset", List.of(), user, chat, expiry)));
            }
            case "prompt_new", "prompt_draft", "prompt_clear" -> {
                rows.add(List.of(button("Prévia", "prompt_preview", List.of(), user, chat, expiry),
                        button("Limpar rascunho", "prompt_clear", List.of(), user, chat, expiry)));
                rows.add(List.of(button("Cancelar rascunho", "prompt_cancel", List.of(), user, chat, expiry)));
            }
            case "prompt_history" -> {
                if (promptSettings != null) for (var revision : promptSettings.latest())
                    rows.add(List.of(button("Restaurar v" + revision.revision(), "prompt_restore",
                            List.of(Integer.toString(revision.revision())), user, chat, expiry)));
            }
            case "settings_ai" -> {
                if (runtimeSettings != null) {
                    RuntimeSettingView setting = runtimeSettings.list().getFirst();
                    int ceiling = (Integer) setting.configuredValue();
                    long version = setting.version();
                    for (int value : new int[]{6, 12, 24, 48, 72, 168}) if (value <= ceiling)
                        rows.add(List.of(button("Contexto " + value + " h", "contexto",
                                List.of(Integer.toString(value), Long.toString(version)), user, chat, expiry)));
                    if ("RUNTIME".equals(setting.source()) || "ENV_CEILING".equals(setting.source()))
                        rows.add(List.of(button("Restaurar contexto", "contexto_default", List.of(Long.toString(version)), user, chat, expiry)));
                }
            }
            case "prompt_list" -> {
                int active = promptSettings == null ? 0 : promptSettings.current(true).revision();
                long version = promptSettings == null ? 0 : promptSettings.activeVersion();
                if (promptSettings != null) for (var revision : promptSettings.latest())
                    if (revision.revision() != active) {
                        rows.add(List.of(button("Ativar v" + revision.revision(),
                                "prompt_activate", List.of(Integer.toString(revision.revision()), Long.toString(version)), user, chat, expiry)));
                    }
                if (promptCalibrationQueue != null && active != 0)
                    rows.add(List.of(button("Testar v" + active, "prompt_test", List.of(Integer.toString(active)), user, chat, expiry)));
                if (promptCalibrationQueue != null)
                    rows.add(List.of(button("Testar default", "prompt_test", List.of("0"), user, chat, expiry)));
                if (promptSettings != null) for (var revision : promptSettings.latest())
                    if (revision.revision() != active && promptCalibrationQueue != null)
                        rows.add(List.of(button("Testar v" + revision.revision(), "prompt_test",
                                List.of(Integer.toString(revision.revision())), user, chat, expiry)));
                rows.add(List.of(button("Prompt atual", "prompt_current", List.of(), user, chat, expiry),
                        button("Novo rascunho", "prompt_new", List.of(), user, chat, expiry)));
            }
            case "prompt_current" -> rows.add(List.of(button("Revisões", "prompt_list", List.of(), user, chat, expiry),
                    button("Prompts", "settings_prompt", List.of(), user, chat, expiry)));
            default -> { }
        }
        if (!List.of("menu", "start").contains(name))
            rows.add(List.of(button(List.of("settings_general", "settings_ai", "settings_prompt", "settings_notifications",
                    "settings_profiles", "settings_availability", "settings_templates", "settings_budget",
                    "settings_security", "settings_audit").contains(name) ? "⬅️ Configurações"
                    : List.of("prompt_current", "prompt_list", "prompt_new", "prompt_page").contains(name) ? "⬅️ Prompts" : "⬅️ Menu principal",
                    List.of("settings_general", "settings_ai", "settings_prompt", "settings_notifications",
                            "settings_profiles", "settings_availability", "settings_templates", "settings_budget",
                            "settings_security", "settings_audit").contains(name) ? "settings"
                            : List.of("prompt_current", "prompt_list", "prompt_new", "prompt_page").contains(name) ? "settings_prompt" : "menu",
                    List.of(), user, chat, expiry)));
        return rows;
    }

    private Map<String, String> button(String text, String name, List<String> args,
                                       long user, long chat, Instant expiry) {
        String token = tokens.issue(user, chat, new CallbackAction("nav_" + name, args), expiry);
        if (token.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64)
            throw new IllegalStateException("Telegram callback_data exceeds 64 bytes");
        return Map.of("text", text, "data", token);
    }

    private TelegramAdminProperties telegramProperties;
    @Autowired(required = false)
    public void configureTelegramMenu(TelegramAdminProperties properties) {
        this.telegramProperties = properties;
    }

    @Autowired(required = false)
    public void configureRuntimeSettings(RuntimeSettingsService settings) { this.runtimeSettings = settings; }

    @Autowired(required = false)
    public void configureAiProperties(dev.hugo.watcher.ai.AiClassificationProperties properties) { this.aiProperties = properties; }

    @Autowired(required = false)
    public void configurePromptSettings(dev.hugo.watcher.ai.ClassificationPromptService settings) { this.promptSettings = settings; }
    @Autowired(required = false)
    public void configurePromptGeneration(dev.hugo.watcher.ai.PromptGenerationService generation) { this.promptGeneration = generation; }

    @Autowired(required = false)
    public void configureContactOverview(ContactPolicyOverviewService service) { this.contactOverview = service; }

    @Autowired(required = false)
    public void configurePromptCalibration(dev.hugo.watcher.ai.PromptCalibrationQueueRepository queue) {
        this.promptCalibrationQueue = queue;
    }

    @Autowired(required = false)
    public void configurePromptGenerationQueue(dev.hugo.watcher.ai.PromptGenerationQueueRepository queue) {
        this.promptGenerationQueue = queue;
    }

    @Autowired(required = false)
    public void configurePromptGenerationMetrics(dev.hugo.watcher.ai.PromptGenerationMetrics metrics) {
        this.promptGenerationMetrics = metrics;
    }

    private static boolean isMutation(String name, List<String> args) {
        return List.of("ocupado", "disponivel", "ferias", "simulacao", "emergencia", "recuperar", "perfil", "template", "feriado", "triagem", "apelido", "contexto", "contexto_default", "prompt_activate", "prompt_restore", "prompt_publish", "prompt_restore_apply", "prompt_reset_apply", "prompt_test").contains(name)
                || (name.equals("regra_notificacao") && !args.isEmpty() && !"listar".equalsIgnoreCase(args.getFirst()))
                || (name.equals("orcamento") && !args.isEmpty());
    }

    private static boolean isPromptLifecycleMutation(String name) {
        return List.of("prompt_publish", "prompt_restore_apply", "prompt_reset_apply", "prompt_activate", "prompt_restore").contains(name);
    }

    /** Reject stale version-bound actions before lifecycle services can throw inside a joined transaction. */
    private void rejectStalePromptTransition(AdminCommand command) {
        if (promptSettings == null) return;
        List<String> args = command.arguments();
        var state = promptSettings.activeState();
        long currentRevision = state.revisionId() == null ? 0 : state.revisionId();
        switch (command.name()) {
            case "prompt_activate" -> {
                if (args.size() == 2 && Long.parseLong(args.get(1)) != state.version()) throw new IllegalStateException("stale_prompt_confirmation");
            }
            case "prompt_reset_apply" -> {
                if (args.size() == 2 && (Long.parseLong(args.get(0)) != currentRevision
                        || Long.parseLong(args.get(1)) != state.version())) throw new IllegalStateException("stale_prompt_confirmation");
            }
            case "prompt_restore_apply" -> {
                if (args.size() == 3 && (Long.parseLong(args.get(1)) != currentRevision
                        || Long.parseLong(args.get(2)) != state.version())) throw new IllegalStateException("stale_prompt_confirmation");
            }
            case "prompt_publish" -> {
                if (args.size() == 4 && (Long.parseLong(args.get(2)) != currentRevision
                        || Long.parseLong(args.get(3)) != state.version())) throw new IllegalStateException("stale_prompt_confirmation");
            }
            default -> { }
        }
    }

    private static int minutes(List<String> args) {
        if (args.size() > 1) throw new IllegalArgumentException("Informe duração em minutos.");
        int minutes = args.isEmpty() ? 60 : Integer.parseInt(args.getFirst());
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("Duração deve ser de 1 a 1440 minutos.");
        return minutes;
    }

    private static String safeError(String command, RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return "Não foi possível aplicar /" + command + ".";
        if (ex instanceof IllegalArgumentException && !message.contains("token")) return message;
        return "Não foi possível aplicar /" + command + ".";
    }

    private static String on(boolean value) { return value ? "ligado" : "desligado"; }
    private static String help() {
        return "/menu, /status, /contexto <horas> <versão>, /contexto_default <versão>, /prompt, /prompt_current, /prompt_list, /prompt_new, /prompt_clear, /prompt_preview, /prompt_cancel, /prompt_history, /prompt_restore <revisão>, /prompt_reset, /prompt_compare <v1> <v2>, /prompt_activate <revisão> <versão-ativa>, /prompt_page <revisão> <posição>, /prompt_test <revisão>, /ocupado [minutos], /disponivel [minutos], /ferias <início> <fim>, /simulacao on|off, /relatorio, /ultimas [1-10], /decisao <id>, /contato <jid>, /triagem <id> on|off (filtro legado; exige confirmação), /emergencia, /recuperar, /perfis, /perfil <chat> <perfil>, /regras_notificacao, /regra_notificacao marcador|contato <valor> WORK_ONLY|ANYTIME|HERDAR NORMAL|PRIORITY, /regra_notificacao remover marcador|contato <valor>, /apelido <jid> <nome...>|remover, /templates, /template <id> on|off, /feriados, /feriado <data> <jurisdição> <fonte-https> <nome>, /orcamento [diário mensal], /confirm <token>.";
    }

}
