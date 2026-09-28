export interface ConnectionLostEvent {
  eventType: "whatsapp_connection_lost";
  generatedAt: string;
  reason: string;
  statusCode?: number;
  manualPairingRequired: boolean;
}

export class ConnectionOutageGuard {
  private connected = false;
  private notified = false;

  constructor(
    private readonly send: (event: ConnectionLostEvent) => Promise<void> | void,
    private readonly log: { warn: (data: object, message?: string) => void } = { warn() {} },
    private readonly now: () => Date = () => new Date(),
  ) {}

  connectionOpened(): void {
    this.connected = true;
    this.notified = false;
  }

  connectionLost(details: { reason: string; statusCode?: number; manualPairingRequired: boolean }): void {
    if (!this.connected || this.notified) return;
    this.connected = false;
    this.notified = true;
    const event: ConnectionLostEvent = {
      eventType: "whatsapp_connection_lost",
      generatedAt: this.now().toISOString(),
      reason: details.reason,
      manualPairingRequired: details.manualPairingRequired,
    };
    if (details.statusCode !== undefined) event.statusCode = details.statusCode;
    void Promise.resolve().then(() => this.send(event)).catch((error: unknown) => {
      this.log.warn({ event: "whatsapp_connection_alert_failed", error: error instanceof Error ? error.name : "UnknownError" }, "Connection alert webhook failed");
    });
  }
}
