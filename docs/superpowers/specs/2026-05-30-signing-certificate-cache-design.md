# Signing Certificate Cache — Design

**Date:** 2026-05-30
**Target branch:** `main` (the cache is self-contained infrastructure; its hot-path consumer lives on `feat/signing` and adopts it as a documented follow-up)
**Status:** Approved design, pending implementation plan

## 1. Problem

On the timestamping hot path, resolving the signing certificate is the one step still backed by an
uncached database read. The other resolved inputs are already Caffeine-cached: the certificate chain
(`certificateChain`), the signature-formatter connector (`connectorApiClient`), the time-quality
configuration (`timeQualityConfiguration`), and the signing/TSP profile models
(`signingProfile` / `tspProfile`). The signing **certificate entity** and its key graph are not.

History confirms this was never cached. From the first timestamping commit, the signer fetched the
certificate per request (`certificateRepository.findByUuid(...)`) and walked the lazy
`key → tokenProfile / items` graph. The later "Introduce cache for resources used by signing" change
added the chain and key-item caches but not a certificate cache. `SigningProfileResolver` did not
remove a cache — it consolidated the same per-request fetch into
`CertificateService.getCertificateEntity()`, routing it through `@ExternalAuthorization` plus an
RA-profile permission check, making the hot-path lookup marginally heavier.

This design adds a dedicated, immutable, internal-only cache for the certificate data the signing
pipeline needs, so a warm timestamping request resolves every input from cache.

## 2. Scope

**Lands on `main`:**

- A new `signingCertificate` Caffeine cache and its configuration.
- An immutable `SigningCertificate` record (certificate data + structural UUID references only).
- A cached, authorization-free accessor `CertificateService.getSigningCertificate(UUID)`.
- Targeted eviction (with a full-clear fallback) on `Certificate` mutations.
- Two additive fields on the existing `CryptographicKeyItemModel` (`type`, `pqcParameterSpecName`)
  plus a small `CryptographyUtil` helper extraction, so the dedicated key-item cache can serve every
  key-item attribute the signer needs **without** caching public-key bytes.
- Unit tests for the accessor, eviction, and record/parity behaviour.

**Documented follow-up on `feat/signing` (NOT in the `main` PR):** rewiring
`SigningProfileResolver`, `ResolvedStaticKeyManagedSigning`, `StaticManagedKeySignerCreator`, and
`StaticManagedKeyCertificateProvider` to assemble their inputs from the caches. See §8.

## 3. Design principles applied

- **Two cooperating caches, no duplicated data.** The `signingCertificate` cache holds only
  certificate-derived data and *structural references* (UUIDs). All volatile key-item attributes
  (state, usage, algorithm, type, derived PQC spec) live in the existing `cryptographicKeyItem`
  cache, which already self-evicts on key-item mutations. The resolver assembles the final view from
  both caches. This keeps each cache's invalidation responsibility narrow.
- **Cache parsed/derived values, not entities.** Matches the existing convention
  (`getCertificateChainForSigning` caches parsed `X509Certificate`s, not `Certificate` rows). No JPA
  entity escapes the accessor; the cached record is immutable and thread-safe.
- **No public-key bytes in any cache.** The only use of public-key `keyData` on the signing path is
  to derive a post-quantum parameter-spec name; we cache that derived string instead of the bytes.

## 4. The caches and their payloads

### 4.1 `signingCertificate` (new) — certificate data + structural references

Keyed by certificate UUID. Holds the certificate's acceptability fields and the UUID references the
resolver needs to look up key material elsewhere. It holds **no** key-item state, usage, algorithm,
type, or key bytes.

```java
public record SigningCertificate(
        UUID uuid,
        String commonName,
        // certificate-level acceptability inputs
        boolean archived,
        CertificateState state,
        CertificateValidationStatus validationStatus,
        List<String> extendedKeyUsageOids,
        Boolean extendedKeyUsageCritical,
        Boolean qcCompliance,
        // structural references (key-level), not key-item material
        UUID keyUuid,
        UUID tokenInstanceReferenceUuid,
        UUID tokenProfileUuid,
        List<UUID> keyItemUuids
) {}
```

`keyItemUuids` lists the UUIDs of the items belonging to this certificate's key. The resolver fetches
each from the key-item cache and classifies it by `type` (see §4.2) — so this record needs no
per-item type or material.

Location: `com.czertainly.core.model.signing` (alongside the other signing models).

### 4.2 `cryptographicKeyItem` (existing, on `main`) — volatile key-item state

Fetched per item via `CryptographicKeyService.getKeyItemModel(UUID)`. Today it carries
`keyItemUuid, state, enabled, usage, keyAlgorithm, keyReferenceUuid, connectorUuid,
tokenInstanceUuid`. Two additive fields are required:

```java
public record CryptographicKeyItemModel(
        UUID keyItemUuid,
        KeyState state,
        boolean enabled,
        List<KeyUsage> usage,
        KeyAlgorithm keyAlgorithm,
        UUID keyReferenceUuid,
        UUID connectorUuid,
        UUID tokenInstanceUuid,
        KeyType type,                 // NEW: lets the resolver classify private vs public after fetch
        String pqcParameterSpecName   // NEW: nullable; set only for FALCON / ML-DSA / SLH-DSA public keys
) {}
```

- **`type`** — the resolver does not need the type to *fetch* an item (`getKeyItemModel` is keyed by
  UUID), only to classify it afterwards. Type is an item attribute, so it belongs here, not in the
  certificate cache.
- **`pqcParameterSpecName`** — see §5.

`keyData` is deliberately **not** added.

## 5. Post-quantum parameter spec instead of `keyData`

`CryptographyUtil.resolveSignatureAlgorithmName(keyAlgorithm, publicKey, attributes)` reads the
`publicKey` (`keyData`) argument only for FALCON, ML-DSA, and SLH-DSA, where it Base64-decodes the
key, parses the `SubjectPublicKeyInfo`, and returns `parameterSpec.getName()`. For RSA and ECDSA the
`keyData` argument is unused — the algorithm name is built from `keyAlgorithm` plus the per-request
digest/scheme signing attributes.

For PQC the derived name depends only on the public key (not on request attributes), so it is stable
and precomputable. We cache that derived string, not the bytes:

- Extract a helper `CryptographyUtil.resolvePqcParameterSpecName(KeyAlgorithm keyAlgorithm,
  String publicKey)` that returns the parameter-spec name for FALCON/ML-DSA/SLH-DSA and `null`
  otherwise. `resolveSignatureAlgorithmName` is refactored to delegate to this helper, so the PQC
  parsing lives in exactly one place.
- `getKeyItemModel` calls the helper once at cache-build time and stores the result in
  `pqcParameterSpecName`.
- The signer then resolves the algorithm as:
  - **PQC:** `SignatureAlgorithm.findByCode(publicItemModel.pqcParameterSpecName())`
  - **RSA / ECDSA:** `resolveSignatureAlgorithmName(keyAlgorithm, null, attributes)` (unchanged)

## 6. The accessor

```java
@Cacheable(value = CacheConfig.SIGNING_CERTIFICATE_CACHE, key = "#certificateUuid", sync = true)
SigningCertificate getSigningCertificate(UUID certificateUuid) throws NotFoundException;
```

on `CertificateService` / `CertificateServiceImpl`.

- **No `@ExternalAuthorization`.** Like `getCertificateChainForSigning`, this is an internal
  signing-only accessor; the TSA authorizes the request at the profile level before resolution. This
  also drops the per-request RA-profile permission check from the hot path.
- Fetches the certificate with a dedicated fetch-join finder (new
  `CertificateRepository.findForSigningByUuid`, joining `key → items` and `key → tokenProfile`)
  inside the cacheable read-only call, maps to the record, and returns it. The managed entity never
  leaves the method, so there is no detached-entity hazard.

## 7. Eviction

`signingCertificate` depends only on the `Certificate` row. Each entry is keyed by the leaf
certificate's own UUID and derives only from that one certificate plus its structural references —
there is **no fan-out** (unlike the chain cache, where one issuer rewiring invalidates many cached
chains, forcing a blanket clear). Volatile key-item state is owned by the `cryptographicKeyItem`
cache, which already self-evicts on key-item mutations. Therefore this cache needs **certificate-
mutation eviction only** — no new key or key-item eviction aspect.

**Strategy: targeted eviction with a full-clear fallback.**

- On a single-entity `Certificate` mutation where the affected UUID is recoverable from the join
  point (`save(entity)`, `deleteById(uuid)`, `@Modifying` updates taking a UUID argument), evict only
  that entry: `cache.evict(certUuid)`.
- On bulk/native paths where per-row UUIDs are not cleanly available (`saveAll`,
  `deleteAllInBatch`, native upserts), fall back to `cache.clear()`.
- Eviction is deferred to `afterCommit` and deduped per transaction, so a rollback leaves the cache
  intact and a bulk operation triggers at most one action.

**Wiring.** Introduce a `SigningCertificateCacheEvictor` mirroring the existing
`CertificateChainCacheEvictor`'s deferred/deduped mechanism but supporting both targeted evict and
clear. The existing `CertificateRepositoryCacheEvictionAspect` (which already intercepts all
`Certificate` repository mutations for the chain cache) also invokes this evictor — passing the
affected UUID when the intercepted method exposes one, otherwise requesting the fallback clear. The
chain cache keeps its existing blanket-clear behaviour; only the new evictor is UUID-aware.

Rare structural key changes (a key item added to or removed from an in-use signing key) are not
certificate mutations; they are backstopped by the TTL. Key *reassignment* updates the certificate
row and is therefore covered by certificate-mutation eviction.

## 8. Configuration

- `CacheConfig`:
  - Constant `SIGNING_CERTIFICATE_CACHE = "signingCertificate"`.
  - Register a Caffeine cache with `expireAfterWrite`, `maximumSize`, and `recordStats`.
  - Add `SigningCertificateCacheProperties` to `@EnableConfigurationProperties` and to the
    `cacheManager` bean parameters.
- New `SigningCertificateCacheProperties` record (prefix `caching.signing-cert`):

  ```java
  @Validated
  @ConfigurationProperties(prefix = "caching.signing-cert")
  public record SigningCertificateCacheProperties(@Min(1) int ttlMinutes, @Min(1) int maxSize) {}
  ```

- `application.yml`, under `caching:`:

  ```yaml
  # Signing-certificate cache holds certificate data + structural references for the signing/timestamping hot path
  signing-cert:
    ttl-minutes: ${SIGNING_CERT_CACHE_TTL_MINUTES:5}
    max-size:    ${SIGNING_CERT_CACHE_MAX_SIZE:1000}
  ```

## 9. Testing

Mirror the existing `CertificateChainCacheTest` / `CertificateChainParityTest` patterns.

- **Accessor / cache test:** build a certificate with an associated key and items, call
  `getSigningCertificate` twice, assert every record field maps correctly and the repository finder
  is invoked exactly once (second call is a cache hit).
- **Eviction test:** a single-entity `Certificate` mutation evicts only the affected entry after
  commit; a bulk mutation triggers the fallback clear; neither acts before commit nor on rollback.
- **Parity test:** the record mapping (plus key-item model assembly) yields the same signer inputs
  and the same acceptability verdict as reading the live entity graph, so the `feat/signing` swap is
  behaviour-preserving.
- **PQC helper test:** `resolvePqcParameterSpecName` returns the correct spec name for FALCON/ML-DSA/
  SLH-DSA public keys and `null` for RSA/ECDSA; `getKeyItemModel` populates `pqcParameterSpecName`
  accordingly.

## 10. `feat/signing` adaptation (follow-up, not in the `main` PR)

- `SigningProfileResolver.resolveScheme` calls `getSigningCertificate(uuid)`, then for each
  `keyItemUuids` entry calls `getKeyItemModel(itemUuid)`, classifies by `type`, and assembles the
  resolved scheme. The manual `Hibernate.initialize(...)` lazy-graph priming is removed.
- `ResolvedStaticKeyManagedSigning` holds the assembled view (`SigningCertificate` + the resolved
  key-item models / chain) instead of a `Certificate` entity.
- Certificate-signing acceptability splits into a certificate-level check fed by `SigningCertificate`
  (archived, state, validation status, EKU, qc-compliance, token-profile present) and a key-item-level
  check fed by the key-item models (each private item ACTIVE and carrying `SIGN`). A
  `SigningCertificate`-based overload of `isCertificateDigitalSigningAcceptable` backs
  `StaticManagedKeyCertificateProvider`; the existing entity-based method remains for the
  profile-save path.
- `StaticManagedKeySignerCreator` reads `keyAlgorithm`, `state`, `usage`, and (for PQC)
  `pqcParameterSpecName` from the key-item models, and `tokenInstanceReferenceUuid` /
  `tokenProfileUuid` from `SigningCertificate`.

## 11. Outcome

A warm timestamping request resolves the signing certificate and its key material entirely from
in-memory caches: `signingCertificate` for certificate data and references, `cryptographicKeyItem`
for each key item's state and derived algorithm spec, alongside the already-cached chain, connector,
time-quality, and profile inputs. No public-key bytes are cached, and each cache owns a single,
narrow invalidation responsibility.
