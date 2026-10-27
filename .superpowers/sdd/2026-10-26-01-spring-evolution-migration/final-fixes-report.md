# CorreÃ§Ãµes da revisÃ£o final

## Escopo e comportamento

- P1: `V10__pending_notification_generation.sql` atribui UUID a cada encarnaÃ§Ã£o de `pending_notification`, incluindo backfill das linhas existentes. Updates de agregaÃ§Ã£o mantÃªm esse UUID; DELETE/reinsert apÃ³s READ, desarquivamento ou merge recebe outro UUID.
- O snapshot interno da outbox carrega `pending_generation`. Cada tentativa HTTP e cada retry durÃ¡vel verifica a geraÃ§Ã£o atual antes de autorizar o payload antigo. Uma geraÃ§Ã£o antiga Ã© suprimida sem apagar a pendÃªncia atual; o prÃ³ximo flush pode criar o lote atual. Outboxes prÃ©-V10 sem geraÃ§Ã£o tambÃ©m falham fechados e mantÃªm a pendÃªncia para recomposiÃ§Ã£o.
- O ACK exige geraÃ§Ã£o e os watermarks anteriores (contagem, primeiro/Ãºltimo timestamp, updated_at). Assim, uma nova linha com campos idÃªnticos nÃ£o Ã© removida, e agregaÃ§Ãµes concorrentes na mesma geraÃ§Ã£o continuam pendentes.
- P2: retirada do LIMIT SQL anterior Ã  polÃ­tica; o limite do lote permanece no preparo apÃ³s nome, grupos e janela. WORK_ONLY fora da janela nÃ£o impede ANYTIME posterior, nem desloca indevidamente um chat durante revalidaÃ§Ã£o.
- P2: `includeGroups=false` Ã© respeitado no preparo e em cada tentativa HTTP, inclusive para pending existente/importado. A supressÃ£o mantÃ©m esse pending.
- Atualizado teste de startup de schema 8 para 10; scheduling desabilitado nesse teste especÃ­fico para evitar polling assÃ­ncrono durante o teardown do PostgreSQL.

## EvidÃªncia TDD e verificaÃ§Ã£o

Ambiente: Maven 3.9.11, Java 25 (`F:/graalvm-25+37-LTS-jvmci-b01`), PostgreSQL 17.11 real via Testcontainers, Docker named pipe local e fake n8n HTTP em loopback. `-DargLine=-Dapi.version=1.44` configura a compatibilidade Docker somente no processo de testes.

1. RED inicial: quatro regressÃµes executadas antes da implementaÃ§Ã£o, quatro falhas de comportamento, zero erros/skips: retry apÃ³s READ/recriaÃ§Ã£o enviava 3 vezes em vez de 1; ANYTIME posterior nÃ£o era enviado; grupo desabilitado enviava 1 vez em vez de 0; desabilitar grupos durante HTTP ainda fazia 3 tentativas em vez de 1. Log: `final-fixes-red.log`.
2. GREEN inicial: `mvn -Dtest=NotificationServiceTest -DargLine=-Dapi.version=1.44 test`: 20 testes, zero falhas/erros/skips.
3. RED adicional do ACK: sem o predicado de geraÃ§Ã£o, a recriaÃ§Ã£o com todos os watermarks idÃªnticos era apagada (esperado 1 pending, obtido 0). Log: `final-fixes-ack-red.log`. O predicado foi entÃ£o aplicado.
4. `mvn -Dtest=NotificationServiceTest,MessageServiceTest,ChatStateServiceTest,IdentityServiceTest,LegacyImportServiceTest,DatabaseMigrationTest,SpringFlywayStartupTest -DargLine=-Dapi.version=1.44 test`: 56 testes, 55 passaram; Ãºnica falha foi a expectativa antiga de versÃ£o 8 no startup (obtido 10), corrigida. NotificationService executou 22 testes verdes, incluindo retry durÃ¡vel apÃ³s recriaÃ§Ã£o e preservaÃ§Ã£o de mensagens concorrentes. Log: `final-fixes-green.log`.
5. `mvn -Dtest=SpringFlywayStartupTest,DatabaseMigrationTest -DargLine=-Dapi.version=1.44 test`: 3 testes verdes, zero falhas/erros/skips, incluindo upgrade V9->V10 com pending existente e payload preservado. O startup ainda registrou EOF/TransactionSystemException do scheduler no teardown; sua configuraÃ§Ã£o de teste foi isolada em seguida. Log: `final-fixes-schema-green.log`.
6. Rechecagem final isolada do startup: `mvn -Dtest=SpringFlywayStartupTest -DargLine=-Dapi.version=1.44 test`, com scheduling desabilitado: 1 teste verde, zero falhas/erros/skips e sem erros do scheduler; resultado em `final-fixes-startup-green.log`. Permanecem avisos da JVM/Testcontainers sobre bootstrap class sharing e CloseableResource.
7. `git diff --check`: sem erros de whitespace; Git apenas avisa sobre normalizaÃ§Ã£o CRLF/LF.

## Limites e estado

- Uma rodada de correÃ§Ãµes, sem suÃ­te completa, sem deploy, VPS, pareamento, envio real ou push.
- A consulta agora carrega todos os pending vencidos/nÃ£o expirados elegÃ­veis por estado antes da filtragem Java. Isso corrige starvation; filas muito grandes podem exigir paginaÃ§Ã£o futura para limitar memÃ³ria. O tamanho do payload HTTP continua limitado por maxBatchSize.
- RevalidaÃ§Ã£o reduz a janela de corrida, mas nÃ£o torna leitura do PostgreSQL e HTTP uma operaÃ§Ã£o atÃ´mica. Eventos apÃ³s a checagem final ou respostas HTTP perdidas ainda podem causar entrega obsoleta/duplicada; nenhuma garantia exactly-once.
- CodeGraph retornou workspace com zero nÃ³s e nenhum sÃ­mbolo/contexto; rastreamento feito por leituras e rg direcionados. RTK.md indicado em C:/Users/vitor.hugo/.codex-cli-private nÃ£o existe neste ambiente; comandos originais usados.
- `.idea/` e `target/` jÃ¡ estavam nÃ£o rastreados e foram preservados. Nenhum arquivo de target foi lido. Logs desta rodada ficam na pasta de evidÃªncias ignorada; somente o relatÃ³rio Ã© incluÃ­do no commit.
