# Introduce the signing certificate cache into `SigningProfileResolver`

Date: 2026-06-02
Status: Approved (design)

## Problem

`SigningProfileResolver.resolveScheme()` resolves a static-key managed timestamping
scheme by loading the full JPA `Certificate` entity
(`certificateService.getCertificateEntity(...)`), eagerly initializing the lazy
`key → tokenProfile / items` graph with `Hibernate.initialize(...)`, and embedding the
detached entity in `ResolvedStaticKeyManagedSigning`. The whole `resolve()` runs under
`@Transactional(readOnly = true)`, so a read connection is held across the downstream
`connectorService` lookup as well.

A signing-certificate cache was added in #1538 but is wired only into tests:

- `CertificateService.getSigningCertificate(UUID)` → `SigningCertificate` record
  (Caffeine cache `signingCertificate`, key = certificate UUID), carrying the
  signing-relevant certificate scalars plus the key / token / token-profile UUIDs and a
  list of `keyItemUuids`.
- `CryptographicKeyService.getKeyItemModel(UUID)` → `CryptographicKeyItemModel` record
  (Caffeine cache `cryptographicKeyItem`, key = key-item UUID), carrying per-item
  `keyAlgorithm`, `keyState`, `keyType`, `keyUsage`, and a pre-computed
  `pqcParameterSpecName` (for PUBLIC_KEY items).
- `CertificateService.getCertificateChainForSigning(UUID, boolean)` is already
  cache-backed (`certificateChain`) and returns `List<X509Certificate>` — no entity.

This change wires those caches into the resolver and migrates the downstream
timestamping pipeline to consume the cached records instead of the JPA entity. The
resolved form remains transient and is never cached.

## Goal & non-goals

**Goal.** `SigningProfileResolver` resolves a static-key scheme entirely from caches; no
JPA `Certificate` / `CryptographicKey` entity flows past the resolver into the
timestamping pipeline. Pipeline behavior (validation strictness, error surfaces) is
preserved.

**Non-goals.** No change to cache configuration, eviction, or the admin/listing path
(`SigningProfileServiceImpl.listSigningCertificates`, which keeps using the entity-based
acceptance check). No new caches. The one-time-key scheme variant remains deferred.

## Field-coverage check

Every entity field the downstream pipeline reads has a cached-record equivalent:

| Downstream read (entity)                          | Cached-record source                              |
|---------------------------------------------------|---------------------------------------------------|
| `certificate.isArchived()`                        | `SigningCertificate.archived()`                   |
| `certificate.getKey() != null`                    | `SigningCertificate.keyUuid() != null`            |
| `certificate.getState()`                          | `SigningCertificate.state()`                       |
| `certificate.getValidationStatus()`              | `SigningCertificate.validationStatus()`           |
| `certificate.getExtendedKeyUsage()` (CSV)        | `SigningCertificate.extendedKeyUsageOids()` (List)|
| `certificate.getExtendedKeyUsageCritical()`      | `SigningCertificate.extendedKeyUsageCritical()`   |
| `certificate.getQcCompliance()`                   | `SigningCertificate.qcCompliance()`               |
| `certificate.getUuid()`                           | `SigningCertificate.uuid()`                        |
| `certificate.getCommonName()`                     | `SigningCertificate.commonName()`                 |
| `key.getTokenProfile() != null`                   | `SigningCertificate.tokenProfileUuid() != null`   |
| `key.getTokenInstanceReferenceUuid()`            | `SigningCertificate.tokenInstanceReferenceUuid()` |
| `key.getTokenProfileUuid()`                       | `SigningCertificate.tokenProfileUuid()`           |
| `key.getUuid()`                                   | `SigningCertificate.keyUuid()`                     |
| `key.getItems()` → item type/state/usage          | `List<CryptographicKeyItemModel>`                 |
| item `keyAlgorithm`                               | `CryptographicKeyItemModel.keyAlgorithm()`        |
| `publicKeyItem.getKeyData()` (PQC spec only)      | `CryptographicKeyItemModel.pqcParameterSpecName()`|

`pqcParameterSpecName` is the only thing `keyData` was used for in the signer path, and
`getKeyItemModel` already pre-computes it — so the raw public-key bytes are never needed
downstream.

## Design

### 1. `ResolvedStaticKeyManagedSigning` (data shape)

Stops carrying the JPA `Certificate`. New shape:

```java
public record ResolvedStaticKeyManagedSigning(
        SigningCertificate certificate,            // cached record (was: Certificate entity)
        List<CryptographicKeyItemModel> keyItems,  // cached per-item records (new)
        List<X509Certificate> chain,
        List<RequestAttribute> signingOperationAttributes
) implements ResolvedManagedScheme {}
```

`keyItems` is added because `SigningCertificate` holds only `keyItemUuids`; the validator
and signer need the per-item type/state/usage/algorithm. A plain list (not pre-split
private/public) is used so the validator can iterate **all** private items and the signer
can pick the first of each type — preserving today's behavior exactly.

### 2. `SigningProfileResolver`

`resolveScheme(...)` replaces entity load + `Hibernate.initialize(...)` with three cache
reads:

1. `SigningCertificate cert = certificateService.getSigningCertificate(certificateUuid)`
2. `List<CryptographicKeyItemModel> keyItems = cert.keyItemUuids().stream()
        .map(cryptographicKeyService::getKeyItemModel).toList()`
3. `List<X509Certificate> chain = certificateService.getCertificateChainForSigning(certificateUuid, true)`

then constructs the new `ResolvedStaticKeyManagedSigning(cert, keyItems, chain, attrs)`.

Error handling unchanged in shape: `NotFoundException` from `getSigningCertificate` or
`getKeyItemModel` → `TspException(SYSTEM_FAILURE, "Signing certificate not found: …",
"Signing key certificate could not be found.")`; empty chain and `CertificateException`
paths unchanged.

`@Transactional(readOnly = true)` is **removed** from `resolve()`: there is no longer a
lazy entity graph to keep attached, and dropping it stops the method from holding a read
tx/connection across the `connectorService` external lookup (per the CLAUDE.md
"no transaction across external calls" rule). Each cache method opens its own short
read-only tx on a miss.

`CryptographicKeyService` is added as a new `@Autowired` setter-injected dependency
(matching the existing injection style in this class). The `Hibernate` and
`CryptographicKey` / `Certificate` entity imports and the `SecuredUUID` wrapping for the
certificate load are removed.

### 3. `CertificateUtil.isCertificateDigitalSigningAcceptable` — new overload

Add a record-based overload:

```java
public static boolean isCertificateDigitalSigningAcceptable(
        SigningCertificate certificate,
        List<CryptographicKeyItemModel> keyItems,
        SigningWorkflowType workflowType,
        boolean qualifiedTimestamp)
```

Logic mirrors the entity overload exactly:

- `archived` false; `keyUuid != null`; `state == ISSUED`; `validationStatus ∈ {VALID, EXPIRING}`.
- `tokenProfileUuid != null` (maps to "key has a token profile assigned").
- Every `PRIVATE_KEY` item in `keyItems` is `ACTIVE` and contains `SIGN`; at least one present.
- `TIMESTAMPING`: `extendedKeyUsageOids == [TIME_STAMPING]` and `extendedKeyUsageCritical == TRUE`.
- Qualified timestamp: `qcCompliance == TRUE`.

The existing entity overload is **retained** — still used by
`SigningProfileServiceImpl.listSigningCertificates` and `CertificateUtilTest`.

### 4. Downstream consumers

- **`StaticManagedKeyCertificateProvider`**
  - `validate(...)`: call the new record overload with
    `signingSchemeModel.certificate()` and `signingSchemeModel.keyItems()`.
  - `getCertificateChain(...)`: `.certificate().getUuid()` → `.certificate().uuid()`.
- **`StaticManagedKeySignerCreator.create(...)`**
  - Guard `cert.keyUuid() != null` (replaces the `getKey() == null` guard); error message
    uses `cert.commonName()`.
  - Private item: first `keyItems` with `keyType() == PRIVATE_KEY` → `keyItemUuid()`, `keyAlgorithm()`.
  - Public item: first `keyItems` with `keyType() == PUBLIC_KEY` → `pqcParameterSpecName()`.
  - Token/key UUIDs from the `SigningCertificate` record
    (`tokenInstanceReferenceUuid()`, `tokenProfileUuid()`, `keyUuid()`).
  - Signature algorithm via the new `CryptographyUtil` overload (below). Same
    `TspFailureInfo.SYSTEM_FAILURE` messages and ordering as today.

### 5. `CryptographyUtil.resolveSignatureAlgorithmName` — new overload

Add:

```java
public static String resolveSignatureAlgorithmName(
        KeyAlgorithm keyAlgorithm,
        List<? extends RequestAttribute> signatureAttributes,
        String pqcParameterSpecName)
```

Identical to the existing `(KeyAlgorithm, String publicKey, …)` method except the
`FALCON | MLDSA | SLHDSA` branch returns the supplied `pqcParameterSpecName` directly
instead of calling `resolvePqcParameterSpecName(keyAlgorithm, publicKey)`. The signer
path no longer needs raw public-key bytes. The existing `publicKey`-based method is
retained (used by `prepareSignatureAlgorithm`).

### 6. Tests

- Update tests that build `ResolvedStaticKeyManagedSigning` to the new four-arg shape and
  feed cached records: `SigningProfileResolverTest`, `StaticManagedKeySignerCreatorTest`,
  `StaticManagedKeyCertificateProviderTest`, `ResolvedSigningProfileTest`,
  `ManagedTimestampEngineTest`, `SignerFactoryTest`, `CertificateProviderFactoryTest`,
  `StaticManagedKeyManagedTimestampTokenGeneratorTest`, `TsaServiceImplTest`,
  `TimestampingSignatureFormatterClientTest`.
- `SigningProfileResolverTest`: assert the resolver calls `getSigningCertificate` +
  `getKeyItemModel` (not `getCertificateEntity`) and no longer runs under a transaction.
- `CertificateUtilTest`: add cases for the new record overload mirroring the existing
  entity parametrized cases.

## Risks

- **Behavior parity of the new validator overload.** Mitigated by mirroring the entity
  logic field-for-field and by `SigningCertificateParityTest` (already verifies the
  record maps the entity graph faithfully) plus new `CertificateUtilTest` record cases.
- **EKU representation.** Entity stores EKU as a serialized CSV; the record exposes a
  parsed `List<String>`. The record overload must apply the same RFC 3161 rule
  (exactly one OID == `TIME_STAMPING`, critical) against the list.
- **Stale cache vs. live entity.** The pipeline now reads cached snapshots (TTL 5 min,
  evicted on certificate / key mutations via the existing aspects). This is the intended
  trade-off of #1538; no new mitigation required.

## Build / verify

Build with `-Dmaven.compiler.proc=full` (per CLAUDE.md). Run the TSA/timestamping unit
suites and `CertificateUtilTest`, plus the cache parity tests.
