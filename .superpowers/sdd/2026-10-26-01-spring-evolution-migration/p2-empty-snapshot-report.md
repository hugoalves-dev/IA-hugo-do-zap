# Revisão P2: valor vazio no caminho do snapshot

O post processor agora usa presença da propriedade como critério de modo seguro, alinhado a `@ConditionalOnProperty`: até um valor vazio força `scheduling-enabled=false` e `web-application-type=none`. O runner valida o valor antes de converter para Path e falha com mensagem clara, fechando o contexto one-shot pelo `finally` sem chamar o importer.

Teste RED/GREEN: inicialmente falharam os dois casos adicionados (scheduling continuava habilitado para propriedade vazia; runner lançava NPE). Após o ajuste, `mvn "-Dtest=WatcherSchedulingConfigurationTest,LegacyImportRunnerTest" test` passou: 7 testes, 0 falhas/erros.

A verificação foi local, sem Compose, VPS, dados legados ou envios.
