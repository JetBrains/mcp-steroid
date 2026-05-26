# TODO Experimental Project Scenarios

Candidate scenarios from recent upstream fixes where IDE indexing, PSI navigation, and reference search should matter more than shell-only search.

## Kill Bill

- [ ] Invoice warning-log behavior: recent upstream work around `test-invoice-warn-logs` and invoice behavior changes. Good candidate because an agent must trace invoice service behavior, tests, and logging expectations across multiple Maven modules.
- [ ] JAX-RS invoice log enum formatting: recent commits changed invoice log enum presentation and removed an unreachable switch default. Good candidate for enum usage/reference search plus API response formatting tests.
- [ ] Account parking on unrecoverable invoice processing failures: recent invoice work spans `InvoiceDispatcher`, `InvoiceListener`, `ParkedAccountsManager`, tenant invoice config, notifier hooks, and tests. Good candidate for reference search across event subscribers and config defaults.
- [ ] End-of-month BCD invoice alignment: recent fixes around `BillingIntervalDetail` and `BillCycleDayCalculator` require finding existing billing-date helpers and updating several invoice/beatrix tests.

## ThingsBoard

- [ ] LwM2M execute-with-args refactoring: recent upstream PR `lwm2m_execute_with_args` added tests and review refactors. Good candidate because protocol request handling spans transport, rule-engine/common DTOs, and tests.
- [ ] PostgreSQL null ordering in entity queries: recent fix crossed SQL query building, YAML config, TypeScript page-link models, and backend tests. Good candidate because an agent must update both server and client semantics.
- [ ] AI-provider SSRF protection: recent fix touched model validation, LangChain4j configuration, controller tests, and service tests. Good candidate for finding all user-controlled provider URL flows.
- [ ] MAX aggregation for mixed telemetry values: recent fix touched TBEL rolling aggregation, timeseries repositories, and service tests. Good candidate for locating every aggregation implementation by symbol/reference search.
- [ ] Kafka consumer empty-assignment busy-wait: recent fix added a focused queue-consumer test. Good candidate for class hierarchy/override search around poll loops and long-polling support.

## BroadleafCommerce

- [ ] CMS Page SiteMap deep-pagination performance fix: recent PR fixed performance degradation in site-map generation. Good candidate because the relevant behavior likely crosses service, DAO/query, and CMS domain layers.
- [ ] Request-parameter JSON escaping: recent fix escaped JSON special characters in request parameters. Good candidate for finding all serialization/URL/request binding paths and verifying test coverage across web modules.
- [ ] Offer targeter/SKU-name special-character validation: recent fix spans validator wiring, admin messages, MVEL helper use, and SKU metadata.
- [ ] Repeated JPA direct-copy class transformation: recent fix in `DirectCopyClassTransformer` is a good candidate for hierarchy and annotation-transform path tracing.
- [ ] Customer auto-login after registration: recent fix in `LoginServiceImpl` requires following registration, request context, and Spring Security context persistence.
