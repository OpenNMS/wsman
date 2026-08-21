# WS-Man Client [![CircleCI](https://circleci.com/gh/OpenNMS/wsman.svg?style=svg)](https://circleci.com/gh/OpenNMS/wsman)

A pure Java WS-Man client implemented using JAX-WS & CXF with support for:
* Enumerate and Pull Operations (DSP8037)
* Get Operations (DSP8035)
* Identify Operation (DSP0226)
* WinRS Shell Command Execution (MS-WSMV)
* Basic, NTLM and SPNEGO Authentication
* Kerberos Message Encryption (MS-WSMV 2.2.9.1), allowing encrypted WS-Man over plain HTTP
* OSGi Compatible

## Java Example

Artifacts are available in Maven Central. Add it to your Maven project using:

```xml
<dependency>
  <groupId>org.opennms.core.wsman</groupId>
  <artifactId>org.opennms.core.wsman.cxf</artifactId>
  <version>1.2.3</version>
</dependency>
```

And start enumerating resources:

```java
package org.opennms.core.wsman.example;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import org.opennms.core.wsman.WSManClient;
import org.opennms.core.wsman.WSManConstants;
import org.opennms.core.wsman.WSManEndpoint;
import org.opennms.core.wsman.WSManVersion;
import org.opennms.core.wsman.cxf.CXFWSManClientFactory;
import org.w3c.dom.Node;

public class WSManClientExample {

    public static void main(String[] args) throws MalformedURLException {
        WSManEndpoint endpoint = new WSManEndpoint.Builder("https://127.0.0.1/wsman")
                .withServerVersion(WSManVersion.WSMAN_1_0)
                .withStrictSSL(false)
                .build();
        WSManClient client = new CXFWSManClientFactory().getClient(endpoint);

        List<Node> nodes = new ArrayList<>();
        client.enumerateAndPullUsingFilter(
                WSManConstants.CIM_ALL_AVAILABLE_CLASSES,
                WSManConstants.XML_NS_WQL_DIALECT,
                "select DeviceDescription,PrimaryStatus,TotalOutputPower,InputVoltage,FirmwareVersion,RedundancyStatus from DCIM_PowerSupplyView where DetailedState != 'Absent' and PrimaryStatus != 0",
                nodes,
                true);
    }
}
```

### Kerberos message encryption and WinRS

Kerberos message encryption protects the SOAP payload itself (MS-WSMV 2.2.9.1), so it is
typically used over plain HTTP on port 5985. Credentials come either from a
username/password pair passed to the builder, or, when no password is given, from a JAAS
login configuration entry named `WSManClient` (set via
`-Djava.security.auth.login.config`, e.g. for keytab or ticket-cache authentication).
Windows WinRM expects the 2004/08 WS-Addressing namespace, so use `WSManVersion.WSMAN_1_0`.

All operations on one client share a single Kerberos session; call `close()` (the client
is `AutoCloseable`) to release the connection, GSS context, and JAAS login. Sessions that
are never closed are reaped automatically after sitting idle (the connection after 60
seconds, everything else after 15 minutes) and re-establish themselves transparently on
next use, so a forgotten `close()` does not pin resources forever; closing promptly is
still preferred.

```java
WSManEndpoint endpoint = new WSManEndpoint.Builder("http://windows-host:5985/wsman")
        .withServerVersion(WSManVersion.WSMAN_1_0)
        .withKerberosEncryption()
        .build();
try (WSManClient client = new CXFWSManClientFactory().getClient(endpoint)) {
    // WS-Man operations work as usual, and WinRS runs remote commands:
    CommandResult result = client.runCommand("ipconfig", new String[] {"/all"}, Duration.ofSeconds(60));
    System.out.println("exit=" + result.exitCode() + " stdout=" + result.stdout());
}
```

## Compiling From Source

Requires Java 8 and Maven 3 (tested with 3.3.3)

```sh
mvn clean package
```

## Using the CLI

Once you've successfully compiled the project, you'll want to locate the .jar artifact provided by the `cli` module:

```sh
export WSMAN_CLI_JAR=cli/target/org.opennms.core.wsman.cli-1.0-SNAPSHOT.jar
```

### Examples

#### Enumeration

Retrieving the computer system details from an iDrac 6 card

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r https://idrac/wsman -u root -p calvin -resourceUri http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_ComputerSystem
```

Retrieving all of the service details from a Windows 2008 Server:

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r http://win2k8:5985 -u Administrator -p PASsW0rdz -resourceUri http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/Win32_Service
```

#### Enumeration with WQL filter

Retrieving details from the active power supply on an iDrac 6 card

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r https://idrac/wsman -u root -p calvin "select DeviceDescription,PrimaryStatus,TotalOutputPower,InputVoltage,Range1MaxInputPower,FirmwareVersion,RedundancyStatus from DCIM_PowerSupplyView where DetailedState != 'Absent' and PrimaryStatus != 0"
```

#### Get with selectors

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r https://idrac/wsman -u root -p calvin -o GET -resourceUri http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_ComputerSystem -s CreationClassName=DCIM_ComputerSystem -s Name=srv:system
```

#### Running a command via WinRS

Everything after `--` is passed to the remote shell verbatim, so command flags don't
collide with the CLI's own options. The command's stdout/stderr are passed through and
its exit code becomes the CLI's exit code. Use `-timeout` to bound the run (seconds,
default 60).

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r http://win-host:5985/wsman -u Administrator -p PASsW0rdz -o SHELL -- ipconfig /all
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r http://win-host:5985/wsman -u Administrator -p PASsW0rdz -o SHELL -timeout 120 -- powershell -Command "Get-Service"
```

#### Kerberos message encryption

Encrypts the SOAP payload itself (MS-WSMV 2.2.9.1), so plain HTTP on port 5985 is safe
to use. Requires a Kerberos setup: point the JVM at a krb5.conf, and either pass
`-u user@REALM -p password` or, for keytab/ticket-cache authentication, omit `-u`/`-p`
and provide a JAAS login configuration with an entry named `WSManClient` via
`-Djava.security.auth.login.config`. Use `-w WSMAN_1_0` so the WS-Addressing headers are
emitted in the 2004/08 namespace Windows expects. Works with all operations, including
`-o SHELL`.

```sh
java -Djava.security.krb5.conf=/etc/krb5.conf \
     -Djava.security.auth.login.config=login.conf \
     -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r http://win-host:5985/wsman -kerberosEncryption \
     -resourceUri http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/Win32_OperatingSystem
```

Example `login.conf` using a keytab:

```
WSManClient {
    com.sun.security.auth.module.Krb5LoginModule required
    useKeyTab=true
    keyTab="/path/to/opennms-ws.keytab"
    principal="opennms-ws@AD.EXAMPLE.COM"
    storeKey=true
    doNotPrompt=true;
};
```

#### Debugging:

Output the WS-Man requests and responses by setting the `-vvv` flag.

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r https://idrac/wsman -u root -p calvin -resourceUri http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_PowerSupplyView -v TRACE -vvv
```
