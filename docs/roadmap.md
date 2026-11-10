# Roadmap

1. **Verify the Phase 1 transport.** Build on Xcode, then record an end-to-end foreground echo and reconnect on physical Galaxy Watch and iPhone hardware. Track failures by hardware and firmware. Evaluate a role-reversed transport if peripheral advertising blocks a target.
2. **Establish peer trust and reconnect behavior.** Design explicit peer verification, authenticated encryption, capability negotiation and revocation. Measure advertising lifetime, battery impact, background restrictions and recovery on each validated target.
3. **Evaluate features independently.** Start with consented device status and battery information. Investigate ANCS authorization, notification behavior, health permissions and watch configuration separately. Publish reproducible evidence before claiming support.
4. **Broaden device coverage.** Validate other Wear OS devices through the transport adapter boundary. Track Apple Watch and Android separately as feasibility research.
