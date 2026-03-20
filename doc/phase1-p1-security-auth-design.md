# Phase 1: P1 Security Authentication Design

## 1. Current State Analysis

### 1.1 Already Implemented (Kerberos)

The following Kerberos/GSSAPI authentication is already implemented in `KafkaConnectConfig.java`:

```java
// Kerberos properties
private String kerberosServiceName = "kafka";
private String kerberosPrincipal;
private String kerberosKeytab;
private String kerberosKrb5Location;

// Security configuration
private String securityProtocol = "PLAINTEXT";
private String saslMechanism = "PLAIN";
private String username;
private String password;
```

**Implementation methods:**
- `applySecurityProperties()` - Routes to appropriate JAAS config
- `applyKerberosJaasConfig()` - Builds Krb5LoginModule JAAS string
- `applyUsernamePasswordJaasConfig()` - Builds PlainLoginModule/ScramLoginModule JAAS string
- `configureKerberosSystemProperties()` - Sets JVM-wide krb5.conf

### 1.2 Unit Tests (Already Passing)

From `KafkaProducerTest.java`:
- `testSaslPlainText_shouldNotIncludeSecurityProps`
- `testSaslPlainConfig_shouldIncludeCorrectProperties`
- `testScramSha256Config_shouldIncludeCorrectProperties`
- `testScramSha512Config_shouldIncludeCorrectProperties`
- `testKerberosConfig_shouldIncludeCorrectJaasConfig`
- `testKerberosWithKrb5Location_shouldSetSystemProperty`
- `testConsumerProperties_shouldAlsoSupportSecurity`
- `testSecurityProtocolDefaultsToPlainText`

---

## 2. SASL/PLAIN Authentication

### 2.1 How It Works

SASL PLAIN uses a simple username/password combination transmitted in plaintext (should only be used over TLS).

### 2.2 JAAS Configuration Template

```java
private void applyUsernamePasswordJaasConfig(Properties props) {
    if (username == null || password == null) {
        log.warn("SASL authentication configured but missing username or password");
        return;
    }

    String jaasConfig;
    if ("PLAIN".equals(saslMechanism)) {
        jaasConfig = String.format(
            "org.apache.kafka.common.security.plain.PlainLoginModule required " +
            "username=\"%s\" password=\"%s\";",
            username, password);
    } else if (saslMechanism.startsWith("SCRAM-")) {
        // SCRAM-SHA-256 or SCRAM-SHA-512
        jaasConfig = String.format(
            "org.apache.kafka.common.security.scram.ScramLoginModule required " +
            "username=\"%s\" password=\"%s\";",
            username, password);
    } else {
        log.warn("Unsupported SASL mechanism: {}", saslMechanism);
        return;
    }

    props.put("sasl.jaas.config", jaasConfig);
}
```

### 2.3 Configuration Example

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        sasl-plain:
          bootstrap-servers: localhost:9092
          security-protocol: SASL_PLAINTEXT
          sasl-mechanism: PLAIN
          username: myuser
          password: mypassword
```

---

## 3. SCRAM-SHA-256/512 Support

### 3.1 How SCRAM Works

SCRAM (Salted Challenge Response Authentication Mechanism) provides:
- Password-based authentication with channel binding
- SCRAM-SHA-256: Uses SHA-256 hash function
- SCRAM-SHA-512: Uses SHA-512 hash function (more secure)

### 3.2 JAAS Configuration

SCRAM uses the same `ScramLoginModule` but with different mechanism:

```java
if ("SCRAM-SHA-256".equals(saslMechanism) || "SCRAM-SHA-512".equals(saslMechanism)) {
    props.put("sasl.mechanism", saslMechanism);
    // JAAS config uses ScramLoginModule
}
```

### 3.3 Configuration Example

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        sasl-scram:
          bootstrap-servers: localhost:9092
          security-protocol: SASL_SSL
          sasl-mechanism: SCRAM-SHA-512
          username: secureuser
          password: securepassword
```

**Note**: SASL_SSL is recommended for SCRAM to encrypt credentials in transit.

---

## 4. Kerberos/GSSAPI (Verified Implementation)

### 4.1 Current Implementation Status

Kerberos authentication is **already implemented** and tested.

**Key Components:**

1. **JAAS Configuration** (`applyKerberosJaasConfig`):
```java
String jaasConfig = String.format(
    "com.sun.security.auth.module.Krb5LoginModule required " +
    "useKeyTab=true storeKey=true serviceName=\"%s\" principal=\"%s\" keyTab=\"%s\";",
    kerberosServiceName, kerberosPrincipal, kerberosKeytab);
props.put("sasl.jaas.config", jaasConfig);
```

2. **System Property Configuration** (`configureKerberosSystemProperties`):
```java
if (kerberosKrb5Location != null && !kerberosKrb5Location.isEmpty()) {
    System.setProperty("java.security.krb5.conf", kerberosKrb5Location);
}
```

3. **Registry Integration** (`KafkaMqEventListenerRegistry.init`):
```java
kafkaConnectConfig.configureKerberosSystemProperties();
```

### 4.2 Configuration Example

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        kerberos-kafka:
          bootstrap-servers: kafka.example.com:9092
          security-protocol: SASL_PLAINTEXT
          sasl-mechanism: GSSAPI
          kerberos-service-name: kafka
          kerberos-principal: kafka/kafka.example.com@EXAMPLE.COM
          kerberos-keytab: /etc/kafka/kafka.keytab
          kerberos-krb5-location: /etc/kafka/krb5.conf
```

---

## 5. Security Protocol Matrix

| Protocol | Encryption | Authentication | Use Case |
|----------|------------|----------------|----------|
| PLAINTEXT | None | None | Development only |
| SASL_PLAINTEXT | None | SASL (PLAIN/SCRAM) | Internal networks |
| SASL_SSL | TLS | SASL (PLAIN/SCRAM) | Production (recommended) |

### 5.1 Authentication Flow

```
Producer/Consumer                    Kafka Broker
      |                                   |
      |--- SASL Handshake ----------------->|
      |<-- Challenge (nonce) ---------------|
      |--- Response (credentials) --------->|
      |<-- Authentication Result -----------|
      |                                   |
      |--- Encrypted/TLS Channel ---------->|
      |     (for SASL_SSL)                 |
```

---

## 6. Configuration Reference

### 6.1 Complete Security Properties

```yaml
shinyi:
  eventbus:
    kafka:
      connect-configs:
        # Authentication type 1: PLAIN
        plain-auth:
          security-protocol: SASL_PLAINTEXT
          sasl-mechanism: PLAIN
          username: user
          password: pass

        # Authentication type 2: SCRAM-SHA-256
        scram256-auth:
          security-protocol: SASL_SSL
          sasl-mechanism: SCRAM-SHA-256
          username: user
          password: pass

        # Authentication type 3: SCRAM-SHA-512
        scram512-auth:
          security-protocol: SASL_SSL
          sasl-mechanism: SCRAM-SHA-512
          username: user
          password: pass

        # Authentication type 4: Kerberos/GSSAPI
        kerberos-auth:
          security-protocol: SASL_PLAINTEXT
          sasl-mechanism: GSSAPI
          kerberos-service-name: kafka
          kerberos-principal: kafka/broker@REALM
          kerberos-keytab: /path/to/keytab
          kerberos-krb5-location: /etc/kafka/krb5.conf
```

---

## 7. Acceptance Criteria

### 7.1 Unit Test Coverage

| Test | Description | Status |
|------|-------------|--------|
| PLAIN auth JAAS config | Verify PlainLoginModule with correct username/password | PASS |
| SCRAM-SHA-256 config | Verify ScramLoginModule with SHA-256 | PASS |
| SCRAM-SHA-512 config | Verify ScramLoginModule with SHA-512 | PASS |
| Kerberos JAAS config | Verify Krb5LoginModule with correct service/principal/keytab | PASS |
| Kerberos krb5.conf | Verify system property is set | PASS |
| PLAINTEXT no security | Verify no security props when PLAINTEXT | PASS |
| Consumer security | Verify security props applied to consumer | PASS |

### 7.2 Integration Test Matrix

| Auth Type | SASL Mechanism | Security Protocol | Test Status |
|-----------|----------------|-------------------|-------------|
| None | N/A | PLAINTEXT | Manual |
| PLAIN | PLAIN | SASL_PLAINTEXT | Manual |
| PLAIN | PLAIN | SASL_SSL | Manual |
| SCRAM | SCRAM-SHA-256 | SASL_SSL | Manual |
| SCRAM | SCRAM-SHA-512 | SASL_SSL | Manual |
| Kerberos | GSSAPI | SASL_PLAINTEXT | Manual |

### 7.3 Security Review Checklist

- [ ] Credentials not logged in plaintext
- [ ] Kerberos keytab file permissions restricted (600)
- [ ] SASL_SSL used in production (not SASL_PLAINTEXT)
- [ ] JAAS config strings not exposed in error messages
- [ ] Kerberos principal follows naming conventions

---

## 8. Reference Implementation

### 8.1 kafka-demo Reference

The kafka-demo project at `/root/.openclaw/workspace-ceo/shinyi-demo/kafka-demo/` contains:
- `KafkaKerberosTest.java` - Kerberos authentication example
- `KafkaProducerPerformanceTest.java` - SASL/PLAIN and SCRAM configuration examples

### 8.2 Key Differences from kafka-demo

| Aspect | kafka-demo | shinyi-eventbus |
|--------|------------|----------------|
| Configuration | Java constants | YAML + Spring |
| Initialization | Manual | Annotation-driven |
| JAAS Config | Hardcoded strings | Dynamic from properties |
| Kerberos Setup | Manual system props | Automatic via config |

---

## 9. Future Enhancements

### 9.1 OAuth/OIDC Support (Phase 4)

```java
// Future: OAuthBearer SASL mechanism
private String saslMechanism = "OAUTHBEARER";
private String oauthTokenEndpoint;
private String oauthClientId;
private String oauthClientSecret;
```

### 9.2 Delegation Token Support

For long-running applications without continuous Kerberos renewal.

---

## 10. Risk Assessment

### 10.1 Kerberos Complexity Risks

| Risk | Severity | Likelihood | Mitigation |
|------|----------|-------------|------------|
| Keytab file expiration | HIGH | MEDIUM | Implement delegation token rotation; monitor keytab expiration via automated alerts |
| Clock skew between nodes | HIGH | MEDIUM | Use NTP synchronization across all Kafka nodes and clients |
| Principal misconfiguration | HIGH | LOW | Validate principal format during startup |
| KDC unavailability | HIGH | LOW | Use multiple KDC servers with proper replication |
| JAAS config parsing errors | MEDIUM | LOW | Use type-safe property building; add startup validation |
| JVM Kerberos ticket cache corruption | MEDIUM | LOW | Clear ticket cache on startup if corruption detected |

**Kerberos Implementation Complexity: HIGH** - Requires understanding of KDC, principals, keytabs, TGT, and session tickets.

### 10.2 SASL/PLAIN Security Risks

| Risk | Severity | Likelihood | Mitigation |
|------|----------|-------------|------------|
| Credentials transmitted in plaintext | HIGH | N/A | NEVER use SASL_PLAINTEXT in production; enforce SASL_SSL |
| Password stored in config files | HIGH | MEDIUM | Use Kubernetes secrets or HashiCorp Vault |
| JAAS config exposed in logs | MEDIUM | LOW | Sanitize all log messages |
| Weak password policy | MEDIUM | MEDIUM | Enforce minimum password complexity |

**SASL/PLAIN Complexity: LOW** - Simple username/password model.

### 10.3 SCRAM Security Considerations

| Risk | Severity | Likelihood | Mitigation |
|------|----------|-------------|------------|
| SHA-256/SHA-512 collision attacks | LOW | LOW | SHA-512 preferred |
| Server impersonation | LOW | LOW | SCRAM channel binding with SASL_SSL |
| Salt generation quality | LOW | LOW | Kafka broker handles salt generation |

**SCRAM Complexity: LOW** - Similar to PLAIN but provides better security.

### 10.4 Keytab File Permission Risks

| Risk | Severity | Likelihood | Mitigation |
|------|----------|-------------|------------|
| World-readable keytab | CRITICAL | LOW | Set file permissions to 600 |
| Keytab ownership mismatch | HIGH | LOW | Ensure keytab owned by application user |
| Keytab on network filesystem | HIGH | MEDIUM | Store keytabs locally |
| Backup exposure | HIGH | LOW | Exclude keytabs from backups |

### 10.5 Combined Risk Summary

| Authentication Type | Implementation Complexity | Recommended Environment |
|--------------------|---------------------------|------------------------|
| PLAIN | LOW | Development only (with SASL_SSL) |
| SCRAM-SHA-256 | LOW | Production (internal) |
| SCRAM-SHA-512 | LOW | Production (recommended) |
| Kerberos/GSSAPI | HIGH | Enterprise with existing AD/KDC |

### 10.6 Security Hardening Recommendations

1. Always use SASL_SSL for SCRAM and PLAIN in non-development environments
2. Rotate Kerberos keytabs before expiration (typically 7-30 days)
3. Implement credential monitoring for all SASL mechanisms
4. Use secrets management (Vault, Kubernetes Secrets) instead of config files
5. Enable audit logging for authentication failures
6. Implement network segmentation - Kafka should not be directly internet-accessible
7. Regular security audits of JAAS configurations and Kerberos principals

---

**Document Version**: 1.1
**Created Date**: 2026-03-19
**Last Updated**: 2026-03-19
**Status**: Security Authentication Complete - Kerberos Verified - Risk Assessment Added
