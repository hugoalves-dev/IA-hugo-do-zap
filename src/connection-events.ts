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
  private notificationTimer?: ReturnType<typeof setTimeout>;

  constructor(
    private readonly send: (event: ConnectionLostEvent) => Promise<void> | void,
    private readonly log: { warn: (data: object, message?: string) => void } = { warn() {} },
    private readonly now: () => Date = () => new Date(),
    private readonly gracePeriodMs = 60_000,
  ) {}

  connectionOpened(): void {
    if (this.notificationTimer) clearTimeout(this.notificationTimer);
    this.notificationTimer = undefined;
    this.connected = true;
    this.notified = false;
  }

  cancelPending(): void {
    if (this.notificationTimer) clearTimeout(this.notificationTimer);
    this.notificationTimer = undefined;
    this.connected = false;
  }

  connectionLost(details: { reason: string; statusCode?: number; manualPairingRequired: boolean }): void {
    if (!this.connected || this.notified || this.notificationTimer) return;
    this.connected = false;
    const notify = () => {
      this.notificationTimer = undefined;
      if (this.connected || this.notified) return;
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
    };
    if (details.manualPairingRequired) notify();
    else this.notificationTimer = setTimeout(notify, this.gracePeriodMs);
  }
}
