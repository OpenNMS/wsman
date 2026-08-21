### 1.3.4 (unreleased)

* Added support for WinRS shell command execution (MS-WSMV): `WSManClient.runCommand()`
  runs a single command on a short-lived remote shell and captures exit code, stdout,
  and stderr. Available from the CLI via `-o SHELL`.
* Added support for Kerberos message encryption (MS-WSMV 2.2.9.1), enabled with
  `WSManEndpoint.Builder.withKerberosEncryption()` or the CLI flag `-kerberosEncryption`.
  Allows WS-Man over plain HTTP (port 5985) with confidentiality and integrity provided
  by GSS-API wrap tokens (AES256-CTS-HMAC-SHA1-96 only). The encrypted transport rides a
  dedicated connection owned by the client; all operations on one client share a single
  Kerberos session and serialize on it.
* `WSManClient` now extends `AutoCloseable`. Call `close()` when done with a client to
  release the Kerberos session (connection, GSS context, JAAS login); the default
  implementation is a no-op for clients without such resources. As a backstop, a shared
  background daemon thread reaps idle Kerberos sessions: the connection is closed after
  60 seconds of inactivity and the GSS context and JAAS login are released after
  15 minutes, so a client that is never closed does not hold resources forever. A reaped
  session re-establishes itself transparently on next use.

### 1.2.1

* Added support for handling XmlFragment elements in pull responses.

### 1.2.0

* Fixed GSS authentication.
* Added support for handling XmlFragment elements in enumeration responses.

### 1.1.1

* Fixed NPE in the exception handling code of org.opennms.core.wsman.cxf.CXFWSManClient.

### 1.1.0

* Improved exceptions.

### 1.0.1

* Initial public release.
